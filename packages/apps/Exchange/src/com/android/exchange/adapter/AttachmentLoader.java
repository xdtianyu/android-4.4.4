/* Copyright (C) 2011 The Android Open Source Project.
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

import android.content.Context;

import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailsync.PartRequest;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.EasSyncService;
import com.android.exchange.utility.UriCodec;
import com.google.common.annotations.VisibleForTesting;

import org.apache.http.HttpStatus;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handle EAS attachment loading, regardless of protocol version
 */
public class AttachmentLoader {
    private final EasSyncService mService;
    private final Context mContext;
    private final Attachment mAttachment;
    private final long mAttachmentId;
    private final int mAttachmentSize;
    private final long mMessageId;
    private final Message mMessage;

    public AttachmentLoader(EasSyncService service, PartRequest req) {
        mService = service;
        mContext = service.mContext;
        mAttachment = req.mAttachment;
        mAttachmentId = mAttachment.mId;
        mAttachmentSize = (int)mAttachment.mSize;
        mMessageId = mAttachment.mMessageKey;
        mMessage = Message.restoreMessageWithId(mContext, mMessageId);
    }

    private void doStatusCallback(int status) {

    }

    private void doProgressCallback(int progress) {

    }

    @VisibleForTesting
    static String encodeForExchange2003(String str) {
        AttachmentNameEncoder enc = new AttachmentNameEncoder();
        StringBuilder sb = new StringBuilder(str.length() + 16);
        enc.appendPartiallyEncoded(sb, str);
        return sb.toString();
    }

    /**
     * Encoder for Exchange 2003 attachment names.  They come from the server partially encoded,
     * but there are still possible characters that need to be encoded (Why, MSFT, why?)
     */
    private static class AttachmentNameEncoder extends UriCodec {
        @Override protected boolean isRetained(char c) {
            // These four characters are commonly received in EAS 2.5 attachment names and are
            // valid (verified by testing); we won't encode them
            return c == '_' || c == ':' || c == '/' || c == '.';
        }
    }

    /**
     * Close, ignoring errors (as during cleanup)
     * @param c a Closeable
     */
    private static void close(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
        }
    }

    /**
     * Save away the contentUri for this Attachment and notify listeners
     * @throws IOException
     */
    private void finishLoadAttachment(File file) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            AttachmentUtilities.saveAttachment(mContext, in, mAttachment);
            doStatusCallback(EmailServiceStatus.SUCCESS);
        } catch (FileNotFoundException e) {
            // Not bloody likely, as we just created it successfully
            throw new IOException("Attachment file not found?");
        } finally {
            close(in);
        }
    }

    /**
     * Loads an attachment, based on the PartRequest passed in the constructor
     * @throws IOException
     */
    public void loadAttachment() throws IOException {
        if (mMessage == null) {
            doStatusCallback(EmailServiceStatus.MESSAGE_NOT_FOUND);
            return;
        }
        // Say we've started loading the attachment
        doProgressCallback(0);

        EasResponse resp = null;
        // The method of attachment loading is different in EAS 14.0 than in earlier versions
        boolean eas14 = mService.mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2010_DOUBLE;
        try {
            if (eas14) {
                Serializer s = new Serializer();
                s.start(Tags.ITEMS_ITEMS).start(Tags.ITEMS_FETCH);
                s.data(Tags.ITEMS_STORE, "Mailbox");
                s.data(Tags.BASE_FILE_REFERENCE, mAttachment.mLocation);
                s.end().end().done(); // ITEMS_FETCH, ITEMS_ITEMS
                resp = mService.sendHttpClientPost("ItemOperations", s.toByteArray());
            } else {
                String location = mAttachment.mLocation;
                // For Exchange 2003 (EAS 2.5), we have to look for illegal chars in the file name
                // that EAS sent to us!
                if (mService.mProtocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                    location = encodeForExchange2003(location);
                }
                String cmd = "GetAttachment&AttachmentName=" + location;
                resp = mService.sendHttpClientPost(cmd, null, EasSyncService.COMMAND_TIMEOUT);
            }

            int status = resp.getStatus();
            if (status == HttpStatus.SC_OK) {
                if (!resp.isEmpty()) {
                    InputStream is = resp.getInputStream();
                    OutputStream os = null;
                    File tmpFile = null;
                    try {
                        tmpFile = File.createTempFile("eas_", "tmp", mContext.getCacheDir());
                        os = new FileOutputStream(tmpFile);
                        if (eas14) {
                            ItemOperationsParser p = new ItemOperationsParser(is, os,
                                    mAttachmentSize, null);
                            p.parse();
                            if (p.getStatusCode() == 1 /* Success */) {
                                finishLoadAttachment(tmpFile);
                                return;
                            }
                        } else {
                            int len = resp.getLength();
                            if (len != 0) {
                                // len > 0 means that Content-Length was set in the headers
                                // len < 0 means "chunked" transfer-encoding
                                ItemOperationsParser.readChunked(is, os,
                                        (len < 0) ? mAttachmentSize : len, null);
                                finishLoadAttachment(tmpFile);
                                return;
                            }
                        }
                    } catch (FileNotFoundException e) {
                        mService.errorLog("Can't get attachment; write file not found?");
                        doStatusCallback(EmailServiceStatus.ATTACHMENT_NOT_FOUND);
                    } finally {
                        close(is);
                        close(os);
                        if (tmpFile != null) {
                            tmpFile.delete();
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Report the error, but also report back to the service
            doStatusCallback(EmailServiceStatus.CONNECTION_ERROR);
            throw e;
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }
}
