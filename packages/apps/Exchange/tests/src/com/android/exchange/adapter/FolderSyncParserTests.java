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

package com.android.exchange.adapter;

import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.service.SyncWindow;
import com.android.exchange.CommandStatusException;
import com.android.exchange.EasSyncService;
import com.android.exchange.provider.EmailContentSetupUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.adapter.FolderSyncParserTests exchange
 */
@MediumTest
public class FolderSyncParserTests extends SyncAdapterTestCase<EmailSyncAdapter> {

    // We increment this to generate unique server id's
    private int mServerIdCount = 0;
    private final long mCreationTime = System.currentTimeMillis();
    private final String[] mMailboxQueryArgs = new String[2];

    public FolderSyncParserTests() {
        super();
    }

    private Mailbox setupBoxSync(int interval, int lookback, String serverId) {
        // Don't save the box; just create it, and give it a server id
        Mailbox box = EmailContentSetupUtils.setupMailbox("box1", mAccount.mId, false,
                mProviderContext, Mailbox.TYPE_MAIL);
        box.mSyncInterval = interval;
        box.mSyncLookback = lookback;
        if (serverId != null) {
            box.mServerId = serverId;
        } else {
            box.mServerId = "serverId-" + mCreationTime + '-' + mServerIdCount++;
        }
        box.save(mProviderContext);
        return box;
    }

    private boolean syncOptionsSame(Mailbox a, Mailbox b) {
        if (a.mSyncInterval != b.mSyncInterval) return false;
        if (a.mSyncLookback != b.mSyncLookback) return false;
        return true;
    }

    public void brokentestSaveAndRestoreMailboxSyncOptions() throws IOException {
        EasSyncService service = getTestService();
        EmailSyncAdapter adapter = new EmailSyncAdapter(service);
        FolderSyncParser parser = new FolderSyncParser(getTestInputStream(), adapter);
        mAccount.save(mProviderContext);

        parser.mAccount = mAccount;
        parser.mAccountId = mAccount.mId;
        parser.mAccountIdAsString = Long.toString(mAccount.mId);
        parser.mContext = mProviderContext;
        parser.mContentResolver = mProviderContext.getContentResolver();

        // Don't save the box; just create it, and give it a server id
        Mailbox box1 = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                null);
        Mailbox box2 = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                null);
        Mailbox boxa = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_1_MONTH,
                null);
        Mailbox boxb = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_2_WEEKS,
                null);
        Mailbox boxc = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_ACCOUNT,
                null);
        Mailbox boxd = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_ACCOUNT,
                null);
        Mailbox boxe = setupBoxSync(Account.CHECK_INTERVAL_PUSH, SyncWindow.SYNC_WINDOW_1_DAY,
                null);

        // Save the options (for a, b, c, d, e);
        parser.saveMailboxSyncOptions();
        // There should be 5 entries in the map, and they should be the correct ones
        assertNotNull(parser.mSyncOptionsMap.get(boxa.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxb.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxc.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxd.mServerId));
        assertNotNull(parser.mSyncOptionsMap.get(boxe.mServerId));

        // Delete all the mailboxes in the account
        ContentResolver cr = mProviderContext.getContentResolver();
        cr.delete(Mailbox.CONTENT_URI, Mailbox.ACCOUNT_KEY + "=?",
                new String[] {parser.mAccountIdAsString});

        // Create new boxes, all with default values for interval & window
        Mailbox box1x = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                box1.mServerId);
        Mailbox box2x = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                box2.mServerId);
        Mailbox boxax = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                boxa.mServerId);
        Mailbox boxbx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                boxb.mServerId);
        Mailbox boxcx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                boxc.mServerId);
        Mailbox boxdx = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                boxd.mServerId);
        Mailbox boxex = setupBoxSync(Account.CHECK_INTERVAL_NEVER, SyncWindow.SYNC_WINDOW_ACCOUNT,
                boxe.mServerId);

        // Restore the sync options
        parser.restoreMailboxSyncOptions();
        box1x = Mailbox.restoreMailboxWithId(mProviderContext, box1x.mId);
        box2x = Mailbox.restoreMailboxWithId(mProviderContext, box2x.mId);
        boxax = Mailbox.restoreMailboxWithId(mProviderContext, boxax.mId);
        boxbx = Mailbox.restoreMailboxWithId(mProviderContext, boxbx.mId);
        boxcx = Mailbox.restoreMailboxWithId(mProviderContext, boxcx.mId);
        boxdx = Mailbox.restoreMailboxWithId(mProviderContext, boxdx.mId);
        boxex = Mailbox.restoreMailboxWithId(mProviderContext, boxex.mId);

        assertTrue(syncOptionsSame(box1, box1x));
        assertTrue(syncOptionsSame(box2, box2x));
        assertTrue(syncOptionsSame(boxa, boxax));
        assertTrue(syncOptionsSame(boxb, boxbx));
        assertTrue(syncOptionsSame(boxc, boxcx));
        assertTrue(syncOptionsSame(boxd, boxdx));
        assertTrue(syncOptionsSame(boxe, boxex));
    }

    private static class MockFolderSyncParser extends FolderSyncParser {
        private BufferedReader mReader;
        private int mDepth = 0;
        private String[] mStack = new String[32];
        private HashMap<String, Integer> mTagMap;


        public MockFolderSyncParser(String fileName, AbstractSyncAdapter adapter)
                throws IOException {
            super(null, adapter);
            AssetManager am = mContext.getAssets();
            InputStream is = am.open(fileName);
            if (is != null) {
                mReader = new BufferedReader(new InputStreamReader(is));
            }
        }

        private void initTagMap() {
            mTagMap = new HashMap<String, Integer>();
            int pageNum = 0;
            for (String[] page: Tags.pages) {
                int tagNum = 5;
                for (String tag: page) {
                    if (mTagMap.containsKey(tag)) {
                        System.err.println("Duplicate tag: " + tag);
                    }
                    int val = (pageNum << Tags.PAGE_SHIFT) + tagNum;
                    mTagMap.put(tag, val);
                    tagNum++;
                }
                pageNum++;
            }
        }

        private int lookupTag(String tagName) {
            if (mTagMap == null) {
                initTagMap();
            }
            int res = mTagMap.get(tagName);
            return res;
        }

        private String getLine() throws IOException {
            while (true) {
                String line = mReader.readLine();
                if (line == null) {
                    return null;
                }
                int start = line.indexOf("| ");
                if (start > 2) {
                    return line.substring(start + 2);
                }
                // Keep looking for a suitable line
            }
        }

        @Override
        public int getValueInt() throws IOException {
            return Integer.parseInt(getValue());
        }

        @Override
        public String getValue() throws IOException {
            String line = getLine();
            if (line == null) throw new IOException();
            int start = line.indexOf(": ");
            if (start < 0) throw new IOException("Line has no value: " + line);
            try {
                return line.substring(start + 2).trim();
            } finally {
                if (nextTag(0) != END) {
                    throw new IOException("Value not followed by end tag: " + name);
                }
            }
        }

        @Override
        public void skipTag() throws IOException {
            if (nextTag(0) == -1) {
                nextTag(0);
            }
        }

        @Override
        public int nextTag(int endingTag) throws IOException {
            String line = getLine();
            if (line == null) {
                return DONE;
            }
            if (line.startsWith("</")) {
                int end = line.indexOf('>');
                String tagName = line.substring(2, end).trim();
                if (!tagName.equals(mStack[--mDepth])) {
                    throw new IOException("Tag end doesn't match tag");
                }
                mStack[mDepth] = null;
                return END;
            } else if (line.startsWith("<")) {
                int end = line.indexOf('>');
                String tagName = line.substring(1, end).trim();
                mStack[mDepth++] = tagName;
                tag = lookupTag(tagName);
                return tag;
            } else {
                return -1;
            }
        }
    }

    private Mailbox getMailboxWithName(String folderName) {
        mMailboxQueryArgs[1] = folderName;
        Cursor c = mResolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION,
                Mailbox.ACCOUNT_KEY + "=? AND " + Mailbox.DISPLAY_NAME + "=?", mMailboxQueryArgs,
                null);
        try {
            assertTrue(c.getCount() == 1);
            c.moveToFirst();
            Mailbox m = new Mailbox();
            m.restore(c);
            return m;
        } finally {
            c.close();
        }
    }

    private boolean isTopLevel(String folderName) {
        Mailbox m = getMailboxWithName(folderName);
        assertNotNull(m);
        return m.mParentKey == Mailbox.NO_MAILBOX;
    }

    private boolean isSubfolder(String parentName, String childName) {
        Mailbox parent = getMailboxWithName(parentName);
        Mailbox child = getMailboxWithName(childName);
        assertNotNull(parent);
        assertNotNull(child);
        assertTrue((parent.mFlags & Mailbox.FLAG_HAS_CHILDREN) != 0);
        return child.mParentKey == parent.mId;
    }

    /**
     * Parse a set of EAS FolderSync commands and create the Mailbox tree accordingly
     *
     * @param fileName the name of the file containing emaillog data for folder sync
     * @throws IOException
     * @throws CommandStatusException
     */
    private void testComplexFolderListParse(String fileName) throws IOException,
            CommandStatusException {
        EasSyncService service = getTestService();
        EmailSyncAdapter adapter = new EmailSyncAdapter(service);
        FolderSyncParser parser = new MockFolderSyncParser(fileName, adapter);
        mAccount.save(mProviderContext);
        mMailboxQueryArgs[0] = Long.toString(mAccount.mId);
        parser.mAccount = mAccount;
        parser.mAccountId = mAccount.mId;
        parser.mAccountIdAsString = Long.toString(mAccount.mId);
        parser.mContext = mProviderContext;
        parser.mContentResolver = mResolver;

        parser.parse();

        assertTrue(isTopLevel("Inbox"));
        assertTrue(isSubfolder("Inbox", "Gecko"));
        assertTrue(isSubfolder("Inbox", "Wombat"));
        assertTrue(isSubfolder("Inbox", "Laslo"));
        assertTrue(isSubfolder("Inbox", "Tomorrow"));
        assertTrue(isSubfolder("Inbox", "Vader"));
        assertTrue(isSubfolder("Inbox", "Personal"));
        assertTrue(isSubfolder("Laslo", "Lego"));
        assertTrue(isSubfolder("Tomorrow", "HomeRun"));
        assertTrue(isSubfolder("Tomorrow", "Services"));
        assertTrue(isSubfolder("HomeRun", "Review"));
        assertTrue(isSubfolder("Vader", "Max"));
        assertTrue(isSubfolder("Vader", "Parser"));
        assertTrue(isSubfolder("Vader", "Scott"));
        assertTrue(isSubfolder("Vader", "Surfing"));
        assertTrue(isSubfolder("Max", "Thomas"));
        assertTrue(isSubfolder("Personal", "Famine"));
        assertTrue(isSubfolder("Personal", "Bar"));
        assertTrue(isSubfolder("Personal", "Bill"));
        assertTrue(isSubfolder("Personal", "Boss"));
        assertTrue(isSubfolder("Personal", "Houston"));
        assertTrue(isSubfolder("Personal", "Mistake"));
        assertTrue(isSubfolder("Personal", "Online"));
        assertTrue(isSubfolder("Personal", "Sports"));
        assertTrue(isSubfolder("Famine", "Buffalo"));
        assertTrue(isSubfolder("Famine", "CornedBeef"));
        assertTrue(isSubfolder("Houston", "Rebar"));
        assertTrue(isSubfolder("Mistake", "Intro"));
    }

    // FolderSyncParserTest.txt is based on customer data (all names changed) that failed to
    // properly create the Mailbox list
    public void brokentestComplexFolderListParse1() throws CommandStatusException, IOException {
        testComplexFolderListParse("FolderSyncParserTest.txt");
    }

    // As above, with the order changed (putting children before parents; a more difficult case
    public void brokentestComplexFolderListParse2() throws CommandStatusException, IOException {
        testComplexFolderListParse("FolderSyncParserTest2.txt");
    }

    // Much larger test (from user with issues related to Type 1 folders)
    public void brokentestComplexFolderListParse3() throws CommandStatusException, IOException {
        EasSyncService service = getTestService();
        EmailSyncAdapter adapter = new EmailSyncAdapter(service);
        FolderSyncParser parser = new MockFolderSyncParser("FolderSyncParserTest3.txt", adapter);
        mAccount.save(mProviderContext);
        mMailboxQueryArgs[0] = Long.toString(mAccount.mId);
        parser.mAccount = mAccount;
        parser.mAccountId = mAccount.mId;
        parser.mAccountIdAsString = Long.toString(mAccount.mId);
        parser.mContext = mProviderContext;
        parser.mContentResolver = mResolver;
        parser.parse();

        int cnt = EmailContent.count(mProviderContext, Mailbox.CONTENT_URI,
                MailboxColumns.ACCOUNT_KEY + "=" + mAccount.mId, null);
        // 270 in the file less 4 "conflicts" folders
        assertEquals(266, cnt);
    }
}
