/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.exchange.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.ServiceProxy;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.Eas;
import com.android.exchange.R.drawable;
import com.android.exchange.R.string;
import com.android.exchange.adapter.PingParser;
import com.android.exchange.eas.EasSyncContacts;
import com.android.exchange.eas.EasSyncCalendar;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasLoadAttachment;
import com.android.exchange.eas.EasMoveItems;
import com.android.exchange.eas.EasOperation;
import com.android.exchange.eas.EasOutboxSync;
import com.android.exchange.eas.EasPing;
import com.android.exchange.eas.EasSearch;
import com.android.exchange.eas.EasSync;
import com.android.exchange.eas.EasSyncBase;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Service for communicating with Exchange servers. There are three main parts of this class:
 * TODO: Flesh out these comments.
 * 1) An {@link AbstractThreadedSyncAdapter} to handle actually performing syncs.
 * 2) Bookkeeping for running Ping requests, which handles push notifications.
 * 3) An {@link IEmailService} Stub to handle RPC from the UI.
 */
public class EmailSyncAdapterService extends AbstractSyncAdapterService {

    private static final String TAG = Eas.LOG_TAG;

    /**
     * Temporary while converting to EasService. Do not check in set to true.
     * When true, delegates various operations to {@link EasService}, for use while developing the
     * new service.
     * The two following fields are used to support what happens when this is true.
     */
    private static final boolean DELEGATE_TO_EAS_SERVICE = false;
    private IEmailService mEasService;
    private ServiceConnection mConnection;

    private static final String EXTRA_START_PING = "START_PING";
    private static final String EXTRA_PING_ACCOUNT = "PING_ACCOUNT";
    private static final long SYNC_ERROR_BACKOFF_MILLIS = 5 * DateUtils.MINUTE_IN_MILLIS;

    /**
     * The amount of time between periodic syncs intended to ensure that push hasn't died.
     */
    private static final long KICK_SYNC_INTERVAL =
            DateUtils.HOUR_IN_MILLIS / DateUtils.SECOND_IN_MILLIS;

    /** Controls whether we do a periodic "kick" to restart the ping. */
    private static final boolean SCHEDULE_KICK = true;

    /** Projection used for getting email address for an account. */
    private static final String[] ACCOUNT_EMAIL_PROJECTION = { AccountColumns.EMAIL_ADDRESS };

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    // Value for a message's server id when sending fails.
    public static final int SEND_FAILED = 1;
    public static final String MAILBOX_KEY_AND_NOT_SEND_FAILED =
            MessageColumns.MAILBOX_KEY + "=? and (" + SyncColumns.SERVER_ID + " is null or " +
            SyncColumns.SERVER_ID + "!=" + SEND_FAILED + ')';

    /**
     * Bookkeeping for handling synchronization between pings and syncs.
     * "Ping" refers to a hanging POST or GET that is used to receive push notifications. Ping is
     * the term for the Exchange command, but this code should be generic enough to be easily
     * extended to IMAP.
     * "Sync" refers to an actual sync command to either fetch mail state, account state, or send
     * mail (send is implemented as "sync the outbox").
     * TODO: Outbox sync probably need not stop a ping in progress.
     * Basic rules of how these interact (note that all rules are per account):
     * - Only one ping or sync may run at a time.
     * - Due to how {@link AbstractThreadedSyncAdapter} works, sync requests will not occur while
     *   a sync is in progress.
     * - On the other hand, ping requests may come in while handling a ping.
     * - "Ping request" is shorthand for "a request to change our ping parameters", which includes
     *   a request to stop receiving push notifications.
     * - If neither a ping nor a sync is running, then a request for either will run it.
     * - If a sync is running, new ping requests block until the sync completes.
     * - If a ping is running, a new sync request stops the ping and creates a pending ping
     *   (which blocks until the sync completes).
     * - If a ping is running, a new ping request stops the ping and either starts a new one or
     *   does nothing, as appopriate (since a ping request can be to stop pushing).
     * - As an optimization, while a ping request is waiting to run, subsequent ping requests are
     *   ignored (the pending ping will pick up the latest ping parameters at the time it runs).
     */
    public class SyncHandlerSynchronizer {
        /**
         * Map of account id -> ping handler.
         * For a given account id, there are three possible states:
         * 1) If no ping or sync is currently running, there is no entry in the map for the account.
         * 2) If a ping is running, there is an entry with the appropriate ping handler.
         * 3) If there is a sync running, there is an entry with null as the value.
         * We cannot have more than one ping or sync running at a time.
         */
        private final HashMap<Long, PingTask> mPingHandlers = new HashMap<Long, PingTask>();

        /**
         * Wait until neither a sync nor a ping is running on this account, and then return.
         * If there's a ping running, actively stop it. (For syncs, we have to just wait.)
         * @param accountId The account we want to wait for.
         */
        private synchronized void waitUntilNoActivity(final long accountId) {
            while (mPingHandlers.containsKey(accountId)) {
                final PingTask pingHandler = mPingHandlers.get(accountId);
                if (pingHandler != null) {
                    pingHandler.stop();
                }
                try {
                    wait();
                } catch (final InterruptedException e) {
                    // TODO: When would this happen, and how should I handle it?
                }
            }
        }

        /**
         * Use this to see if we're currently syncing, as opposed to pinging or doing nothing.
         * @param accountId The account to check.
         * @return Whether that account is currently running a sync.
         */
        private synchronized boolean isRunningSync(final long accountId) {
            return (mPingHandlers.containsKey(accountId) && mPingHandlers.get(accountId) == null);
        }

        /**
         * If there are no running pings, stop the service.
         */
        private void stopServiceIfNoPings() {
            for (final PingTask pingHandler : mPingHandlers.values()) {
                if (pingHandler != null) {
                    return;
                }
            }
            EmailSyncAdapterService.this.stopSelf();
        }

        /**
         * Called prior to starting a sync to update our bookkeeping. We don't actually run the sync
         * here; the caller must do that.
         * @param accountId The account on which we are running a sync.
         */
        public synchronized void startSync(final long accountId) {
            waitUntilNoActivity(accountId);
            mPingHandlers.put(accountId, null);
        }

        /**
         * Starts or restarts a ping for an account, if the current account state indicates that it
         * wants to push.
         * @param account The account whose ping is being modified.
         */
        public synchronized void modifyPing(final boolean lastSyncHadError,
                final Account account) {
            // If a sync is currently running, it will start a ping when it's done, so there's no
            // need to do anything right now.
            if (isRunningSync(account.mId)) {
                return;
            }

            // Don't ping if we're on security hold.
            if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                return;
            }

            // Don't ping for accounts that haven't performed initial sync.
            if (EmailContent.isInitialSyncKey(account.mSyncKey)) {
                return;
            }

            // Determine if this account needs pushes. All of the following must be true:
            // - The account's sync interval must indicate that it wants push.
            // - At least one content type must be sync-enabled in the account manager.
            // - At least one mailbox of a sync-enabled type must have automatic sync enabled.
            final EmailSyncAdapterService service = EmailSyncAdapterService.this;
            final android.accounts.Account amAccount = new android.accounts.Account(
                            account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            boolean pushNeeded = false;
            if (account.mSyncInterval == Account.CHECK_INTERVAL_PUSH) {
                final HashSet<String> authsToSync = getAuthsToSync(amAccount);
                // If we have at least one sync-enabled content type, check for syncing mailboxes.
                if (!authsToSync.isEmpty()) {
                    final Cursor c = Mailbox.getMailboxesForPush(service.getContentResolver(),
                            account.mId);
                    if (c != null) {
                        try {
                            while (c.moveToNext()) {
                                final int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                                if (authsToSync.contains(Mailbox.getAuthority(mailboxType))) {
                                    pushNeeded = true;
                                    break;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                }
            }

            // Stop, start, or restart the ping as needed, as well as the ping kicker periodic sync.
            final PingTask pingSyncHandler = mPingHandlers.get(account.mId);
            final Bundle extras = new Bundle(1);
            extras.putBoolean(Mailbox.SYNC_EXTRA_PUSH_ONLY, true);
            if (pushNeeded) {
                // First start or restart the ping as appropriate.
                if (pingSyncHandler != null) {
                    pingSyncHandler.restart();
                } else {
                    if (lastSyncHadError) {
                        // Schedule an alarm to set up the ping in 5 minutes
                        scheduleDelayedPing(amAccount, SYNC_ERROR_BACKOFF_MILLIS);
                    } else {
                        // Start a new ping.
                        // Note: unlike startSync, we CANNOT allow the caller to do the actual work.
                        // If we return before the ping starts, there's a race condition where
                        // another ping or sync might start first. It only works for startSync
                        // because sync is higher priority than ping (i.e. a ping can't start while
                        // a sync is pending) and only one sync can run at a time.
                        final PingTask pingHandler = new PingTask(service, account, amAccount,
                                this);
                        mPingHandlers.put(account.mId, pingHandler);
                        pingHandler.start();
                        // Whenever we have a running ping, make sure this service stays running.
                        service.startService(new Intent(service, EmailSyncAdapterService.class));
                    }
                }
                if (SCHEDULE_KICK) {
                    ContentResolver.addPeriodicSync(amAccount, EmailContent.AUTHORITY, extras,
                               KICK_SYNC_INTERVAL);
                }
            } else {
                if (pingSyncHandler != null) {
                    pingSyncHandler.stop();
                }
                if (SCHEDULE_KICK) {
                    ContentResolver.removePeriodicSync(amAccount, EmailContent.AUTHORITY, extras);
                }
            }
        }

        /**
         * Updates the synchronization bookkeeping when a sync is done.
         * @param account The account whose sync just finished.
         */
        public synchronized void syncComplete(final boolean lastSyncHadError,
                final Account account) {
            LogUtils.d(TAG, "syncComplete, err: " + lastSyncHadError);
            mPingHandlers.remove(account.mId);
            // Syncs can interrupt pings, so we should check if we need to start one now.
            // If the last sync had a fatal error, we will not immediately recreate the ping.
            // Instead, we'll set an alarm that will restart them in a few minutes. This prevents
            // a battery draining spin if there is some kind of protocol error or other
            // non-transient failure. (Actually, immediately pinging even for a transient error
            // isn't great)
            modifyPing(lastSyncHadError, account);
            stopServiceIfNoPings();
            notifyAll();
        }

        /**
         * Updates the synchronization bookkeeping when a ping is done. Also requests a ping-only
         * sync if necessary.
         * @param amAccount The {@link android.accounts.Account} for this account.
         * @param accountId The account whose ping just finished.
         * @param pingStatus The status value from {@link PingParser} for the last ping performed.
         *                   This cannot be one of the values that results in another ping, so this
         *                   function only needs to handle the terminal statuses.
         */
        public synchronized void pingComplete(final android.accounts.Account amAccount,
                final long accountId, final int pingStatus) {
            mPingHandlers.remove(accountId);

            // TODO: if (pingStatus == PingParser.STATUS_FAILED), notify UI.
            // TODO: if (pingStatus == PingParser.STATUS_REQUEST_TOO_MANY_FOLDERS), notify UI.

            if (pingStatus == EasOperation.RESULT_REQUEST_FAILURE ||
                    pingStatus == EasOperation.RESULT_OTHER_FAILURE) {
                // TODO: Sticky problem here: we necessarily aren't in a sync, so it's impossible to
                // signal the error to the SyncManager and take advantage of backoff there. Worse,
                // the current mechanism for how we do this will just encourage spammy requests
                // since the actual ping-only sync request ALWAYS succeeds.
                // So for now, let's delay a bit before asking the SyncManager to perform the sync.
                // Longer term, this should be incorporated into some form of backoff, either
                // by integrating with the SyncManager more fully or by implementing a Ping-specific
                // backoff mechanism (e.g. integrate this with the logic for ping duration).
                LogUtils.e(TAG, "Ping for account %d completed with error %d, delaying next ping",
                        accountId, pingStatus);
                scheduleDelayedPing(amAccount, SYNC_ERROR_BACKOFF_MILLIS);
            } else {
                stopServiceIfNoPings();
            }

            // TODO: It might be the case that only STATUS_CHANGES_FOUND and
            // STATUS_FOLDER_REFRESH_NEEDED need to notifyAll(). Think this through.
            notifyAll();
        }

    }
    private final SyncHandlerSynchronizer mSyncHandlerMap = new SyncHandlerSynchronizer();

    /**
     * The binder for IEmailService.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        private String getEmailAddressForAccount(final long accountId) {
            final String emailAddress = Utility.getFirstRowString(EmailSyncAdapterService.this,
                    Account.CONTENT_URI, ACCOUNT_EMAIL_PROJECTION, Account.ID_SELECTION,
                    new String[] {Long.toString(accountId)}, null, 0);
            if (emailAddress == null) {
                LogUtils.e(TAG, "Could not find email address for account %d", accountId);
            }
            return emailAddress;
        }

        @Override
        public Bundle validate(final HostAuth hostAuth) {
            LogUtils.d(TAG, "IEmailService.validate");
            if (mEasService != null) {
                try {
                    return mEasService.validate(hostAuth);
                } catch (final RemoteException re) {
                    LogUtils.e(TAG, re, "While asking EasService to handle validate");
                }
            }
            return new EasFolderSync(EmailSyncAdapterService.this, hostAuth).doValidate();
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            LogUtils.d(TAG, "IEmailService.autoDiscover");
            return new EasAutoDiscover(EmailSyncAdapterService.this, username, password)
                    .doAutodiscover();
        }

        @Override
        public void updateFolderList(final long accountId) {
            LogUtils.d(TAG, "IEmailService.updateFolderList: %d", accountId);
            if (mEasService != null) {
                try {
                    mEasService.updateFolderList(accountId);
                    return;
                } catch (final RemoteException re) {
                    LogUtils.e(TAG, re, "While asking EasService to updateFolderList");
                }
            }
            final String emailAddress = getEmailAddressForAccount(accountId);
            if (emailAddress != null) {
                final Bundle extras = new Bundle(1);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                ContentResolver.requestSync(new android.accounts.Account(
                        emailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE),
                        EmailContent.AUTHORITY, extras);
            }
        }

        @Override
        public void setLogging(final int flags) {
            // TODO: fix this?
            // Protocol logging
            Eas.setUserDebug(flags);
            // Sync logging
            //setUserDebug(flags);
        }

        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long accountId,
                final long attachmentId, final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
            // TODO: Prevent this from happening in parallel with a sync?
            final EasLoadAttachment operation = new EasLoadAttachment(EmailSyncAdapterService.this,
                    accountId, attachmentId, callback);
            operation.performOperation();
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse: %d, %d", messageId, response);
            EasMeetingResponder.sendMeetingResponse(EmailSyncAdapterService.this, messageId,
                    response);
        }

        /**
         * Delete PIM (calendar, contacts) data for the specified account
         *
         * @param emailAddress the email address for the account whose data should be deleted
         */
        @Override
        public void deleteAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
            if (emailAddress != null) {
                final Context context = EmailSyncAdapterService.this;
                EasSyncContacts.wipeAccountFromContentProvider(context, emailAddress);
                EasSyncCalendar.wipeAccountFromContentProvider(context, emailAddress);
            }
            // TODO: Run account reconciler?
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            LogUtils.d(TAG, "IEmailService.searchMessages");
            final EasSearch operation = new EasSearch(EmailSyncAdapterService.this, accountId,
                    searchParams, destMailboxId);
            operation.performOperation();
            return operation.getTotalResults();
            // TODO: may need an explicit callback to replace the one to IEmailServiceCallback.
        }

        @Override
        public void sendMail(final long accountId) {}

        @Override
        public void pushModify(final long accountId) {
            LogUtils.d(TAG, "IEmailService.pushModify");
            if (mEasService != null) {
                try {
                    mEasService.pushModify(accountId);
                    return;
                } catch (final RemoteException re) {
                    LogUtils.e(TAG, re, "While asking EasService to handle pushModify");
                }
            }
            final Account account = Account.restoreAccountWithId(EmailSyncAdapterService.this,
                    accountId);
            if (account != null) {
                mSyncHandlerMap.modifyPing(false, account);
            }
        }

        @Override
        public void sync(final long accountId, final boolean updateFolderList,
                final int mailboxType, final long[] folders) {}
    };

    public EmailSyncAdapterService() {
        super();
    }

    /**
     * {@link AsyncTask} for restarting pings for all accounts that need it.
     */
    private static final String PUSH_ACCOUNTS_SELECTION =
            AccountColumns.SYNC_INTERVAL + "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);
    private class RestartPingsTask extends AsyncTask<Void, Void, Void> {

        private final ContentResolver mContentResolver;
        private final SyncHandlerSynchronizer mSyncHandlerMap;
        private boolean mAnyAccounts;

        public RestartPingsTask(final ContentResolver contentResolver,
                final SyncHandlerSynchronizer syncHandlerMap) {
            mContentResolver = contentResolver;
            mSyncHandlerMap = syncHandlerMap;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final Cursor c = mContentResolver.query(Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, PUSH_ACCOUNTS_SELECTION, null, null);
            if (c != null) {
                try {
                    mAnyAccounts = (c.getCount() != 0);
                    while (c.moveToNext()) {
                        final Account account = new Account();
                        account.restore(c);
                        mSyncHandlerMap.modifyPing(false, account);
                    }
                } finally {
                    c.close();
                }
            } else {
                mAnyAccounts = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!mAnyAccounts) {
                LogUtils.d(TAG, "stopping for no accounts");
                EmailSyncAdapterService.this.stopSelf();
            }
        }
    }

    @Override
    public void onCreate() {
        LogUtils.v(TAG, "onCreate()");
        super.onCreate();
        startService(new Intent(this, EmailSyncAdapterService.class));
        // Restart push for all accounts that need it.
        new RestartPingsTask(getContentResolver(), mSyncHandlerMap).executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR);
        if (DELEGATE_TO_EAS_SERVICE) {
            // TODO: This block is temporary to support the transition to EasService.
            mConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name,  IBinder binder) {
                    mEasService = IEmailService.Stub.asInterface(binder);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mEasService = null;
                }
            };
            bindService(new Intent(this, EasService.class), mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    public void onDestroy() {
        LogUtils.v(TAG, "onDestroy()");
        super.onDestroy();
        for (PingTask task : mSyncHandlerMap.mPingHandlers.values()) {
            if (task != null) {
                task.stop();
            }
        }
        if (DELEGATE_TO_EAS_SERVICE) {
            // TODO: This block is temporary to support the transition to EasService.
            unbindService(mConnection);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getAction().equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION)) {
            return mBinder;
        }
        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null &&
                TextUtils.equals(Eas.EXCHANGE_SERVICE_INTENT_ACTION, intent.getAction())) {
            if (intent.getBooleanExtra(ServiceProxy.EXTRA_FORCE_SHUTDOWN, false)) {
                // We've been asked to forcibly shutdown. This happens if email accounts are
                // deleted, otherwise we can get errors if services are still running for
                // accounts that are now gone.
                // TODO: This is kind of a hack, it would be nicer if we could handle it correctly
                // if accounts disappear out from under us.
                LogUtils.d(TAG, "Forced shutdown, killing process");
                System.exit(-1);
            } else if (intent.getBooleanExtra(EXTRA_START_PING, false)) {
                LogUtils.d(TAG, "Restarting ping from alarm");
                // We've been woken up by an alarm to restart our ping. This happens if a sync
                // fails, rather that instantly starting the ping, we'll hold off for a few minutes.
                final android.accounts.Account account =
                        intent.getParcelableExtra(EXTRA_PING_ACCOUNT);
                EasPing.requestPing(account);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected AbstractThreadedSyncAdapter getSyncAdapter() {
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new SyncAdapterImpl(this);
            }
            return sSyncAdapter;
        }
    }

    // TODO: Handle cancelSync() appropriately.
    private class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(final android.accounts.Account acct, final Bundle extras,
                final String authority, final ContentProviderClient provider,
                final SyncResult syncResult) {
            if (LogUtils.isLoggable(TAG, Log.DEBUG)) {
                LogUtils.d(TAG, "onPerformSync: %s, %s", acct.toString(), extras.toString());
            } else {
                LogUtils.i(TAG, "onPerformSync: %s", extras.toString());
            }
            TempDirectory.setTempDirectory(EmailSyncAdapterService.this);

            // TODO: Perform any connectivity checks, bail early if we don't have proper network
            // for this sync operation.

            final Context context = getContext();
            final ContentResolver cr = context.getContentResolver();

            // Get the EmailContent Account
            final Account account;
            final Cursor accountCursor = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                    AccountColumns.EMAIL_ADDRESS + "=?", new String[] {acct.name}, null);
            try {
                if (!accountCursor.moveToFirst()) {
                    // Could not load account.
                    // TODO: improve error handling.
                    LogUtils.w(TAG, "onPerformSync: could not load account");
                    return;
                }
                account = new Account();
                account.restore(accountCursor);
            } finally {
                accountCursor.close();
            }

            // Figure out what we want to sync, based on the extras and our account sync status.
            final boolean isInitialSync = EmailContent.isInitialSyncKey(account.mSyncKey);
            final long[] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
            final int mailboxType = extras.getInt(Mailbox.SYNC_EXTRA_MAILBOX_TYPE,
                    Mailbox.TYPE_NONE);

            // Push only means this sync request should only refresh the ping (either because
            // settings changed, or we need to restart it for some reason).
            final boolean pushOnly = Mailbox.isPushOnlyExtras(extras);
            // Account only means just do a FolderSync.
            final boolean accountOnly = Mailbox.isAccountOnlyExtras(extras);

            // A "full sync" means that we didn't request a more specific type of sync.
            final boolean isFullSync = (!pushOnly && !accountOnly && mailboxIds == null &&
                    mailboxType == Mailbox.TYPE_NONE);

            // A FolderSync is necessary for full sync, initial sync, and account only sync.
            final boolean isFolderSync = (isFullSync || isInitialSync || accountOnly);

            // If we're just twiddling the push, we do the lightweight thing and bail early.
            if (pushOnly && !isFolderSync) {
                LogUtils.d(TAG, "onPerformSync: mailbox push only");
                if (mEasService != null) {
                    try {
                        mEasService.pushModify(account.mId);
                        return;
                    } catch (final RemoteException re) {
                        LogUtils.e(TAG, re, "While trying to pushModify within onPerformSync");
                    }
                }
                mSyncHandlerMap.modifyPing(false, account);
                return;
            }

            // Do the bookkeeping for starting a sync, including stopping a ping if necessary.
            mSyncHandlerMap.startSync(account.mId);
            int operationResult = 0;
            try {
                // Perform a FolderSync if necessary.
                // TODO: We permit FolderSync even during security hold, because it's necessary to
                // resolve some holds. Ideally we would only do it for the holds that require it.
                if (isFolderSync) {
                    final EasFolderSync folderSync = new EasFolderSync(context, account);
                    operationResult = folderSync.doFolderSync();
                    if (operationResult < 0) {
                        return;
                    }
                }

                // Do not permit further syncs if we're on security hold.
                if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                    return;
                }

                // Perform email upsync for this account. Moves first, then state changes.
                if (!isInitialSync) {
                    EasMoveItems move = new EasMoveItems(context, account);
                    operationResult = move.upsyncMovedMessages();
                    if (operationResult < 0) {
                        return;
                    }

                    // TODO: EasSync should eventually handle both up and down; for now, it's used
                    // purely for upsync.
                    EasSync upsync = new EasSync(context, account);
                    operationResult = upsync.upsync();
                    if (operationResult < 0) {
                        return;
                    }
                }

                if (mailboxIds != null) {
                    final boolean hasCallbackMethod =
                            extras.containsKey(EmailServiceStatus.SYNC_EXTRAS_CALLBACK_METHOD);
                    // Sync the mailbox that was explicitly requested.
                    for (final long mailboxId : mailboxIds) {
                        if (hasCallbackMethod) {
                            EmailServiceStatus.syncMailboxStatus(cr, extras, mailboxId,
                                    EmailServiceStatus.IN_PROGRESS, 0,
                                    UIProvider.LastSyncResult.SUCCESS);
                        }
                        operationResult = syncMailbox(context, cr, acct, account, mailboxId,
                                extras, syncResult, null, true);
                        if (hasCallbackMethod) {
                            EmailServiceStatus.syncMailboxStatus(cr, extras,
                                    mailboxId,EmailServiceStatus.SUCCESS, 0,
                                    EasOperation.translateSyncResultToUiResult(operationResult));
                        }

                        if (operationResult < 0) {
                            break;
                        }
                    }
                } else if (!accountOnly && !pushOnly) {
                    // We have to sync multiple folders.
                    final Cursor c;
                    if (isFullSync) {
                        // Full account sync includes all mailboxes that participate in system sync.
                        c = Mailbox.getMailboxIdsForSync(cr, account.mId);
                    } else {
                        // Type-filtered sync should only get the mailboxes of a specific type.
                        c = Mailbox.getMailboxIdsForSyncByType(cr, account.mId, mailboxType);
                    }
                    if (c != null) {
                        try {
                            final HashSet<String> authsToSync = getAuthsToSync(acct);
                            while (c.moveToNext()) {
                                operationResult = syncMailbox(context, cr, acct, account,
                                        c.getLong(0), extras, syncResult, authsToSync, false);
                                if (operationResult < 0) {
                                    break;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                }
            } finally {
                // Clean up the bookkeeping, including restarting ping if necessary.
                mSyncHandlerMap.syncComplete(syncResult.hasError(), account);

                if (operationResult < 0) {
                    EasFolderSync.writeResultToSyncResult(operationResult, syncResult);
                    // If any operations had an auth error, notify the user.
                    // Note that provisioning errors should have already triggered the policy
                    // notification, so suppress those from showing the auth notification.
                    if (syncResult.stats.numAuthExceptions > 0 &&
                            operationResult != EasOperation.RESULT_PROVISIONING_ERROR) {
                        showAuthNotification(account.mId, account.mEmailAddress);
                    }
                }

                LogUtils.d(TAG, "onPerformSync: finished");
            }
        }

        /**
         * Update the mailbox's sync status with the provider and, if we're finished with the sync,
         * write the last sync time as well.
         * @param context Our {@link Context}.
         * @param mailbox The mailbox whose sync status to update.
         * @param cv A {@link ContentValues} object to use for updating the provider.
         * @param syncStatus The status for the current sync.
         */
        private void updateMailbox(final Context context, final Mailbox mailbox,
                final ContentValues cv, final int syncStatus) {
            cv.put(Mailbox.UI_SYNC_STATUS, syncStatus);
            if (syncStatus == EmailContent.SYNC_STATUS_NONE) {
                cv.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
            }
            mailbox.update(context, cv);
        }

        private int syncMailbox(final Context context, final ContentResolver cr,
                final android.accounts.Account acct, final Account account, final long mailboxId,
                final Bundle extras, final SyncResult syncResult, final HashSet<String> authsToSync,
                final boolean isMailboxSync) {
            final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
            if (mailbox == null) {
                return EasSyncBase.RESULT_HARD_DATA_FAILURE;
            }

            if (mailbox.mAccountKey != account.mId) {
                LogUtils.e(TAG, "Mailbox does not match account: %s, %s", acct.toString(),
                        extras.toString());
                return EasSyncBase.RESULT_HARD_DATA_FAILURE;
            }
            if (authsToSync != null && !authsToSync.contains(Mailbox.getAuthority(mailbox.mType))) {
                // We are asking for an account sync, but this mailbox type is not configured for
                // sync. Do NOT treat this as a sync error for ping backoff purposes.
                return EasSyncBase.RESULT_DONE;
            }

            if (mailbox.mType == Mailbox.TYPE_DRAFTS) {
                // TODO: Because we don't have bidirectional sync working, trying to downsync
                // the drafts folder is confusing. b/11158759
                // For now, just disable all syncing of DRAFTS type folders.
                // Automatic syncing should always be disabled, but we also stop it here to ensure
                // that we won't sync even if the user attempts to force a sync from the UI.
                // Do NOT treat as a sync error for ping backoff purposes.
                LogUtils.d(TAG, "Skipping sync of DRAFTS folder");
                return EasSyncBase.RESULT_DONE;
            }

            // Non-mailbox syncs are whole account syncs initiated by the AccountManager and are
            // treated as background syncs.
            // TODO: Push will be treated as "user" syncs, and probably should be background.
            if (mailbox.mType == Mailbox.TYPE_OUTBOX || mailbox.isSyncable()) {
                final ContentValues cv = new ContentValues(2);
                updateMailbox(context, mailbox, cv, isMailboxSync ?
                        EmailContent.SYNC_STATUS_USER : EmailContent.SYNC_STATUS_BACKGROUND);
                try {
                    if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
                        return syncOutbox(context, cr, account, mailbox);
                    }
                    final EasSyncBase operation = new EasSyncBase(context, account, mailbox);
                    return operation.performOperation();
                } finally {
                    updateMailbox(context, mailbox, cv, EmailContent.SYNC_STATUS_NONE);
                }
            }

            return EasSyncBase.RESULT_DONE;
        }
    }

    private int syncOutbox(Context context, ContentResolver cr, Account account, Mailbox mailbox) {
        // Get a cursor to Outbox messages
        final Cursor c = cr.query(Message.CONTENT_URI,
                Message.CONTENT_PROJECTION, MAILBOX_KEY_AND_NOT_SEND_FAILED,
                new String[] {Long.toString(mailbox.mId)}, null);
        try {
            // Loop through the messages, sending each one
            while (c.moveToNext()) {
                final Message message = new Message();
                message.restore(c);
                if (Utility.hasUnloadedAttachments(context, message.mId)) {
                    // We'll just have to wait on this...
                    continue;
                }

                // TODO: Fix -- how do we want to signal to UI that we started syncing?
                // Note the entire callback mechanism here needs improving.
                //sendMessageStatus(message.mId, null, EmailServiceStatus.IN_PROGRESS, 0);

                EasOperation op = new EasOutboxSync(context, account, message, true);
                int result = op.performOperation();
                if (result == EasOutboxSync.RESULT_ITEM_NOT_FOUND) {
                    // This can happen if we are using smartReply, and the message we are referring
                    // to has disappeared from the server. Try again with smartReply disabled.
                    op = new EasOutboxSync(context, account, message, false);
                    result = op.performOperation();
                }
                // If we got some connection error or other fatal error, terminate the sync.
                if (result != EasOutboxSync.RESULT_OK &&
                    result != EasOutboxSync.RESULT_NON_FATAL_ERROR &&
                    result > EasOutboxSync.RESULT_OP_SPECIFIC_ERROR_RESULT) {
                    LogUtils.w(TAG, "Aborting outbox sync for error %d", result);
                    return result;
                }
            }
        } finally {
            // TODO: Some sort of sendMessageStatus() is needed here.
            c.close();
        }
        return EasOutboxSync.RESULT_OK;
    }

    private void showAuthNotification(long accountId, String accountName) {
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                createAccountSettingsIntent(accountId, accountName),
                0);

        final Notification notification = new Builder(this)
                .setContentTitle(this.getString(string.auth_error_notification_title))
                .setContentText(this.getString(
                        string.auth_error_notification_text, accountName))
                .setSmallIcon(drawable.stat_notify_auth)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        final NotificationManager nm = (NotificationManager)
                this.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify("AuthError", 0, notification);
    }

    /**
     * Create and return an intent to display (and edit) settings for a specific account, or -1
     * for any/all accounts.  If an account name string is provided, a warning dialog will be
     * displayed as well.
     */
    public static Intent createAccountSettingsIntent(long accountId, String accountName) {
        final Uri.Builder builder = IntentUtilities.createActivityIntentUrlBuilder(
                IntentUtilities.PATH_SETTINGS);
        IntentUtilities.setAccountId(builder, accountId);
        IntentUtilities.setAccountName(builder, accountName);
        return new Intent(Intent.ACTION_EDIT, builder.build());
    }

    /**
     * Determine which content types are set to sync for an account.
     * @param account The account whose sync settings we're looking for.
     * @return The authorities for the content types we want to sync for account.
     */
    private static HashSet<String> getAuthsToSync(final android.accounts.Account account) {
        final HashSet<String> authsToSync = new HashSet();
        if (ContentResolver.getSyncAutomatically(account, EmailContent.AUTHORITY)) {
            authsToSync.add(EmailContent.AUTHORITY);
        }
        if (ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY)) {
            authsToSync.add(CalendarContract.AUTHORITY);
        }
        if (ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)) {
            authsToSync.add(ContactsContract.AUTHORITY);
        }
        return authsToSync;
    }

    /**
     * Schedule to have a ping start some time in the future. This is used when we encounter an
     * error, and properly should be a more full featured back-off, but for the short run, just
     * waiting a few minutes at least avoids burning battery.
     * @param amAccount The account that needs to be pinged.
     * @param delay The time in milliseconds to wait before requesting the ping-only sync. Note that
     *              it may take longer than this before the ping actually happens, since there's two
     *              layers of waiting ({@link AlarmManager} can choose to wait longer, as can the
     *              SyncManager).
     */
    private void scheduleDelayedPing(final android.accounts.Account amAccount, final long delay) {
        final Intent intent = new Intent(this, EmailSyncAdapterService.class);
        intent.setAction(Eas.EXCHANGE_SERVICE_INTENT_ACTION);
        intent.putExtra(EXTRA_START_PING, true);
        intent.putExtra(EXTRA_PING_ACCOUNT, amAccount);
        final PendingIntent pi = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT);
        final AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        final long atTime = SystemClock.elapsedRealtime() + delay;
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atTime, pi);
    }
}
