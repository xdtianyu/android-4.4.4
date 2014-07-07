/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.ServiceProxy;
import com.android.exchange.Eas;
import com.android.exchange.eas.EasFolderSync;
import com.android.exchange.eas.EasLoadAttachment;
import com.android.exchange.eas.EasOperation;
import com.android.exchange.eas.EasSearch;
import com.android.mail.utils.LogUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Service to handle all communication with the EAS server. Note that this is completely decoupled
 * from the sync adapters; sync adapters should make blocking calls on this service to actually
 * perform any operations.
 */
public class EasService extends Service {

    private static final String TAG = Eas.LOG_TAG;

    /**
     * The content authorities that can be synced for EAS accounts. Initialization must wait until
     * after we have a chance to call {@link EmailContent#init} (and, for future content types,
     * possibly other initializations) because that's how we can know what the email authority is.
     */
    private static String[] AUTHORITIES_TO_SYNC;

    /** Bookkeeping for ping tasks & sync threads management. */
    private final PingSyncSynchronizer mSynchronizer;

    /**
     * Implementation of the IEmailService interface.
     * For the most part these calls should consist of creating the correct {@link EasOperation}
     * class and calling {@link #doOperation} with it.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {
        @Override
        public void sendMail(final long accountId) {
            LogUtils.d(TAG, "IEmailService.sendMail: %d", accountId);
        }

        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long accountId,
                final long attachmentId, final boolean background) {
            LogUtils.d(TAG, "IEmailService.loadAttachment: %d", attachmentId);
            final EasLoadAttachment operation = new EasLoadAttachment(EasService.this, accountId,
                    attachmentId, callback);
            doOperation(operation, "IEmailService.loadAttachment");
        }

        @Override
        public void updateFolderList(final long accountId) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, accountId);
            doOperation(operation, "IEmailService.updateFolderList");
        }

        @Override
        public void sync(final long accountId, final boolean updateFolderList,
                final int mailboxType, final long[] folders) {}

        @Override
        public void pushModify(final long accountId) {
            LogUtils.d(TAG, "IEmailService.pushModify: %d", accountId);
            final Account account = Account.restoreAccountWithId(EasService.this, accountId);
            if (pingNeededForAccount(account)) {
                mSynchronizer.pushModify(account);
            } else {
                mSynchronizer.pushStop(accountId);
            }
        }

        @Override
        public Bundle validate(final HostAuth hostAuth) {
            final EasFolderSync operation = new EasFolderSync(EasService.this, hostAuth);
            doOperation(operation, "IEmailService.validate");
            return operation.getValidationResult();
        }

        @Override
        public int searchMessages(final long accountId, final SearchParams searchParams,
                final long destMailboxId) {
            final EasSearch operation = new EasSearch(EasService.this, accountId, searchParams,
                    destMailboxId);
            doOperation(operation, "IEmailService.searchMessages");
            return operation.getTotalResults();
        }

        @Override
        public void sendMeetingResponse(final long messageId, final int response) {
            LogUtils.d(TAG, "IEmailService.sendMeetingResponse: %d, %d", messageId, response);
        }

        @Override
        public Bundle autoDiscover(final String username, final String password) {
            LogUtils.d(TAG, "IEmailService.autoDiscover");
            return null;
        }

        @Override
        public void setLogging(final int flags) {
            LogUtils.d(TAG, "IEmailService.setLogging");
        }

        @Override
        public void deleteAccountPIMData(final String emailAddress) {
            LogUtils.d(TAG, "IEmailService.deleteAccountPIMData");
        }
    };

    /**
     * Content selection string for getting all accounts that are configured for push.
     * TODO: Add protocol check so that we don't get e.g. IMAP accounts here.
     * (Not currently necessary but eventually will be.)
     */
    private static final String PUSH_ACCOUNTS_SELECTION =
            EmailContent.AccountColumns.SYNC_INTERVAL +
                    "=" + Integer.toString(Account.CHECK_INTERVAL_PUSH);

    /** {@link AsyncTask} to restart pings for all accounts that need it. */
    private class RestartPingsTask extends AsyncTask<Void, Void, Void> {
        private boolean mHasRestartedPing = false;

        @Override
        protected Void doInBackground(Void... params) {
            final Cursor c = EasService.this.getContentResolver().query(Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, PUSH_ACCOUNTS_SELECTION, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final Account account = new Account();
                        account.restore(c);
                        if (EasService.this.pingNeededForAccount(account)) {
                            mHasRestartedPing = true;
                            EasService.this.mSynchronizer.pushModify(account);
                        }
                    }
                } finally {
                    c.close();
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!mHasRestartedPing) {
                LogUtils.d(TAG, "RestartPingsTask did not start any pings.");
                EasService.this.mSynchronizer.stopServiceIfIdle();
            }
        }
    }

    public EasService() {
        super();
        mSynchronizer = new PingSyncSynchronizer(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EmailContent.init(this);
        AUTHORITIES_TO_SYNC = new String[] {
                EmailContent.AUTHORITY,
                CalendarContract.AUTHORITY,
                ContactsContract.AUTHORITY
        };

        // Restart push for all accounts that need it. Because this requires DB loads, we do it in
        // an AsyncTask, and we startService to ensure that we stick around long enough for the
        // task to complete. The task will stop the service if necessary after it's done.
        startService(new Intent(this, EasService.class));
        new RestartPingsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        mSynchronizer.stopAllPings();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
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
            }
        }
        return START_STICKY;
    }

    public int doOperation(final EasOperation operation, final String loggingName) {
        final long accountId = operation.getAccountId();
        final Account account = operation.getAccount();
        LogUtils.d(TAG, "%s: %d", loggingName, accountId);
        mSynchronizer.syncStart(accountId);
        // TODO: Do we need a wakelock here? For RPC coming from sync adapters, no -- the SA
        // already has one. But for others, maybe? Not sure what's guaranteed for AIDL calls.
        // If we add a wakelock (or anything else for that matter) here, must remember to undo
        // it in the finally block below.
        // On the other hand, even for SAs, it doesn't hurt to get a wakelock here.
        try {
            return operation.performOperation();
        } finally {
            mSynchronizer.syncEnd(account);
        }
    }

    /**
     * Determine whether this account is configured with folders that are ready for push
     * notifications.
     * @param account The {@link Account} that we're interested in.
     * @return Whether this account needs to ping.
     */
    public boolean pingNeededForAccount(final Account account) {
        // Check account existence.
        if (account == null || account.mId == Account.NO_ACCOUNT) {
            LogUtils.d(TAG, "Do not ping: Account not found or not valid");
            return false;
        }

        // Check if account is configured for a push sync interval.
        if (account.mSyncInterval != Account.CHECK_INTERVAL_PUSH) {
            LogUtils.d(TAG, "Do not ping: Account %d not configured for push", account.mId);
            return false;
        }

        // Check security hold status of the account.
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            LogUtils.d(TAG, "Do not ping: Account %d is on security hold", account.mId);
            return false;
        }

        // Check if the account has performed at least one sync so far (accounts must perform
        // the initial sync before push is possible).
        if (EmailContent.isInitialSyncKey(account.mSyncKey)) {
            LogUtils.d(TAG, "Do not ping: Account %d has not done initial sync", account.mId);
            return false;
        }

        // Check that there's at least one mailbox that is both configured for push notifications,
        // and whose content type is enabled for sync in the account manager.
        final android.accounts.Account amAccount = new android.accounts.Account(
                        account.mEmailAddress, Eas.EXCHANGE_ACCOUNT_MANAGER_TYPE);

        final Set<String> authsToSync = getAuthoritiesToSync(amAccount, AUTHORITIES_TO_SYNC);
        // If we have at least one sync-enabled content type, check for syncing mailboxes.
        if (!authsToSync.isEmpty()) {
            final Cursor c = Mailbox.getMailboxesForPush(getContentResolver(), account.mId);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        final int mailboxType = c.getInt(Mailbox.CONTENT_TYPE_COLUMN);
                        if (authsToSync.contains(Mailbox.getAuthority(mailboxType))) {
                            return true;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        LogUtils.d(TAG, "Do not ping: Account %d has no folders configured for push", account.mId);
        return false;
    }

    /**
     * Determine which content types are set to sync for an account.
     * @param account The account whose sync settings we're looking for.
     * @param authorities All possible authorities we could care about.
     * @return The authorities for the content types we want to sync for account.
     */
    private static Set<String> getAuthoritiesToSync(final android.accounts.Account account,
            final String[] authorities) {
        final HashSet<String> authsToSync = new HashSet();
        for (final String authority : authorities) {
            if (ContentResolver.getSyncAutomatically(account, authority)) {
                authsToSync.add(authority);
            }
        }
        return authsToSync;
    }
}
