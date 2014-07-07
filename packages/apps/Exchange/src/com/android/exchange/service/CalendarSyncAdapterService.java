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

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.CalendarContract.Events;
import android.util.Log;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.exchange.Eas;
import com.android.mail.utils.LogUtils;

public class CalendarSyncAdapterService extends AbstractSyncAdapterService {
    private static final String TAG = Eas.LOG_TAG;
    private static final String ACCOUNT_AND_TYPE_CALENDAR =
        MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.TYPE + '=' + Mailbox.TYPE_CALENDAR;
    private static final String DIRTY_IN_ACCOUNT =
        Events.DIRTY + "=1 AND " + Events.ACCOUNT_NAME + "=?";

    private static final Object sSyncAdapterLock = new Object();
    private static AbstractThreadedSyncAdapter sSyncAdapter = null;

    public CalendarSyncAdapterService() {
        super();
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

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {
        public SyncAdapterImpl(Context context) {
            super(context, true /* autoInitialize */);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {
            if (LogUtils.isLoggable(TAG, Log.DEBUG)) {
                LogUtils.d(TAG, "onPerformSync Calendar starting %s, %s", account.toString(),
                        extras.toString());
            } else {
                LogUtils.i(TAG, "onPerformSync Calendar starting %s", extras.toString());
            }
            CalendarSyncAdapterService.performSync(getContext(), account, extras);
            LogUtils.d(TAG, "onPerformSync Calendar finished");
        }
    }

    /**
     * Partial integration with system SyncManager; we tell our EAS ExchangeService to start a
     * calendar sync when we get the signal from SyncManager.
     * The missing piece at this point is integration with the push/ping mechanism in EAS; this will
     * be put in place at a later time.
     */
    private static void performSync(Context context, Account account, Bundle extras) {
        if (extras.getBoolean(Mailbox.SYNC_EXTRA_NOOP, false)) {
            LogUtils.d(TAG, "No-op sync requested, done");
            return;
        }

        final ContentResolver cr = context.getContentResolver();
        final boolean logging = Eas.USER_LOG;
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
            final Cursor c = cr.query(Events.CONTENT_URI,
                    new String[] {Events._ID}, DIRTY_IN_ACCOUNT, new String[] {account.name}, null);
            if (c == null) {
                LogUtils.e(TAG, "Null changes cursor in CalendarSyncAdapterService");
                return;
            }
            try {
                if (!c.moveToFirst()) {
                    if (logging) {
                        LogUtils.d(TAG, "No changes for " + account.name);
                    }
                    return;
                }
            } finally {
                c.close();
            }
        }

        // Forward the sync request to the EmailSyncAdapterService.
        final long [] mailboxIds = Mailbox.getMailboxIdsFromBundle(extras);
        final Bundle mailExtras;
        if (mailboxIds == null) {
            // We weren't given any particular mailboxId, specify a sync for all calendars.
            mailExtras = new Bundle();
            mailExtras.putInt(Mailbox.SYNC_EXTRA_MAILBOX_TYPE, Mailbox.TYPE_CALENDAR);
        } else {
            // Otherwise, add all of the mailboxes specified in the original sync extras.
            mailExtras = Mailbox.createSyncBundle(mailboxIds);
        }
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
            mailExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        }
        ContentResolver.requestSync(account, EmailContent.AUTHORITY, mailExtras);
        LogUtils.d(TAG, "requestSync CalendarSyncAdapter %s", mailExtras.toString());
    }
}
