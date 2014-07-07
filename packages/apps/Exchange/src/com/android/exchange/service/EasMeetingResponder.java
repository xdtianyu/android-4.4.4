package com.android.exchange.service;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Events;

import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MeetingInfo;
import com.android.emailcommon.mail.PackedString;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceConstants;
import com.android.emailcommon.utility.Utility;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.exchange.adapter.MeetingResponseParser;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;
import com.android.exchange.utility.CalendarUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;

import java.io.IOException;
import java.security.cert.CertificateException;

/**
 * Responds to a meeting request, both notifying the EAS server and sending email.
 */
public class EasMeetingResponder extends EasServerConnection {

    private static final String TAG = Eas.LOG_TAG;

    /** Projection for getting the server id for a mailbox. */
    private static final String[] MAILBOX_SERVER_ID_PROJECTION = { MailboxColumns.SERVER_ID };
    private static final int MAILBOX_SERVER_ID_COLUMN = 0;

    /** EAS protocol values for UserResponse. */
    private static final int EAS_RESPOND_ACCEPT = 1;
    private static final int EAS_RESPOND_TENTATIVE = 2;
    private static final int EAS_RESPOND_DECLINE = 3;

    /** Value to use if we get a UI response value that we can't handle. */
    private static final int EAS_RESPOND_UNKNOWN = -1;

    private EasMeetingResponder(final Context context, final Account account) {
        super(context, account);
    }

    /**
     * Translate from {@link UIProvider.MessageOperations} constants to EAS values.
     * They're currently identical but this is for future-proofing.
     * @param messageOperationResponse The response value that came from the UI.
     * @return The EAS protocol value to use.
     */
    private static int messageOperationResponseToUserResponse(final int messageOperationResponse) {
        switch (messageOperationResponse) {
            case UIProvider.MessageOperations.RESPOND_ACCEPT:
                return EAS_RESPOND_ACCEPT;
            case UIProvider.MessageOperations.RESPOND_TENTATIVE:
                return EAS_RESPOND_TENTATIVE;
            case UIProvider.MessageOperations.RESPOND_DECLINE:
                return EAS_RESPOND_DECLINE;
        }
        return EAS_RESPOND_UNKNOWN;
    }

    /**
     * Send the response to both the EAS server and as email (if appropriate).
     * @param context Our {@link Context}.
     * @param messageId The db id for the message containing the meeting request.
     * @param response The UI's value for the user's response to the meeting.
     */
    public static void sendMeetingResponse(final Context context, final long messageId,
            final int response) {
        final int easResponse = messageOperationResponseToUserResponse(response);
        if (easResponse == EAS_RESPOND_UNKNOWN) {
            LogUtils.e(TAG, "Bad response value: %d", response);
            return;
        }
        final Message msg = Message.restoreMessageWithId(context, messageId);
        if (msg == null) {
            LogUtils.d(TAG, "Could not load message %d", messageId);
            return;
        }
        final Account account = Account.restoreAccountWithId(context, msg.mAccountKey);
        if (account == null) {
            LogUtils.e(TAG, "Could not load account %d for message %d", msg.mAccountKey, msg.mId);
            return;
        }
        final String mailboxServerId = Utility.getFirstRowString(context,
                ContentUris.withAppendedId(Mailbox.CONTENT_URI, msg.mMailboxKey),
                MAILBOX_SERVER_ID_PROJECTION, null, null, null, MAILBOX_SERVER_ID_COLUMN);
        if (mailboxServerId == null) {
            LogUtils.e(TAG, "Could not load mailbox %d for message %d", msg.mMailboxKey, msg.mId);
            return;
        }

        final EasMeetingResponder responder = new EasMeetingResponder(context, account);
        try {
            responder.sendResponse(msg, mailboxServerId, easResponse);
        } catch (final IOException e) {
            LogUtils.e(TAG, "IOException: %s", e.getMessage());
        } catch (final CertificateException e) {
            LogUtils.e(TAG, "CertificateException: %s", e.getMessage());
        }
    }

    /**
     * Send an email response to a meeting invitation.
     * @param meetingInfo The meeting info that was extracted from the invitation message.
     * @param response The EAS value for the user's response to the meeting.
     */
    private void sendMeetingResponseMail(final PackedString meetingInfo, final int response) {
        // This will come as "First Last" <box@server.blah>, so we use Address to
        // parse it into parts; we only need the email address part for the ics file
        final Address[] addrs = Address.parse(meetingInfo.get(MeetingInfo.MEETING_ORGANIZER_EMAIL));
        // It shouldn't be possible, but handle it anyway
        if (addrs.length != 1) return;
        final String organizerEmail = addrs[0].getAddress();

        final String dtStamp = meetingInfo.get(MeetingInfo.MEETING_DTSTAMP);
        final String dtStart = meetingInfo.get(MeetingInfo.MEETING_DTSTART);
        final String dtEnd = meetingInfo.get(MeetingInfo.MEETING_DTEND);

        // What we're doing here is to create an Entity that looks like an Event as it would be
        // stored by CalendarProvider
        final ContentValues entityValues = new ContentValues(6);
        final Entity entity = new Entity(entityValues);

        // Fill in times, location, title, and organizer
        entityValues.put("DTSTAMP",
                CalendarUtilities.convertEmailDateTimeToCalendarDateTime(dtStamp));
        entityValues.put(Events.DTSTART, Utility.parseEmailDateTimeToMillis(dtStart));
        entityValues.put(Events.DTEND, Utility.parseEmailDateTimeToMillis(dtEnd));
        entityValues.put(Events.EVENT_LOCATION, meetingInfo.get(MeetingInfo.MEETING_LOCATION));
        entityValues.put(Events.TITLE, meetingInfo.get(MeetingInfo.MEETING_TITLE));
        entityValues.put(Events.ORGANIZER, organizerEmail);

        // Add ourselves as an attendee, using our account email address
        final ContentValues attendeeValues = new ContentValues(2);
        attendeeValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ATTENDEE);
        attendeeValues.put(Attendees.ATTENDEE_EMAIL, mAccount.mEmailAddress);
        entity.addSubValue(Attendees.CONTENT_URI, attendeeValues);

        // Add the organizer
        final ContentValues organizerValues = new ContentValues(2);
        organizerValues.put(Attendees.ATTENDEE_RELATIONSHIP,
                Attendees.RELATIONSHIP_ORGANIZER);
        organizerValues.put(Attendees.ATTENDEE_EMAIL, organizerEmail);
        entity.addSubValue(Attendees.CONTENT_URI, organizerValues);

        // Create a message from the Entity we've built.  The message will have fields like
        // to, subject, date, and text filled in.  There will also be an "inline" attachment
        // which is in iCalendar format
        final int flag;
        switch(response) {
            case EmailServiceConstants.MEETING_REQUEST_ACCEPTED:
                flag = Message.FLAG_OUTGOING_MEETING_ACCEPT;
                break;
            case EmailServiceConstants.MEETING_REQUEST_DECLINED:
                flag = Message.FLAG_OUTGOING_MEETING_DECLINE;
                break;
            case EmailServiceConstants.MEETING_REQUEST_TENTATIVE:
            default:
                flag = Message.FLAG_OUTGOING_MEETING_TENTATIVE;
                break;
        }
        final Message outgoingMsg =
            CalendarUtilities.createMessageForEntity(mContext, entity, flag,
                    meetingInfo.get(MeetingInfo.MEETING_UID), mAccount);
        // Assuming we got a message back (we might not if the event has been deleted), send it
        if (outgoingMsg != null) {
            sendMessage(mAccount, outgoingMsg);
        }
    }

    /**
     * Send the response to the EAS server, and also via email if requested.
     * @param msg The email message for the meeting invitation.
     * @param mailboxServerId The server id for the mailbox that msg is in.
     * @param response The EAS value for the user's response.
     * @throws IOException
     */
    private void sendResponse(final Message msg, final String mailboxServerId, final int response)
            throws IOException, CertificateException {
        final Serializer s = new Serializer();
        s.start(Tags.MREQ_MEETING_RESPONSE).start(Tags.MREQ_REQUEST);
        s.data(Tags.MREQ_USER_RESPONSE, Integer.toString(response));
        s.data(Tags.MREQ_COLLECTION_ID, mailboxServerId);
        s.data(Tags.MREQ_REQ_ID, msg.mServerId);
        s.end().end().done();
        final EasResponse resp = sendHttpClientPost("MeetingResponse", s.toByteArray());
        try {
            final int status = resp.getStatus();
            if (status == HttpStatus.SC_OK) {
                if (!resp.isEmpty()) {
                    // TODO: Improve the parsing to actually handle error statuses.
                    new MeetingResponseParser(resp.getInputStream()).parse();

                    if (msg.mMeetingInfo != null) {
                        final PackedString meetingInfo = new PackedString(msg.mMeetingInfo);
                        final String responseRequested =
                                meetingInfo.get(MeetingInfo.MEETING_RESPONSE_REQUESTED);
                        // If there's no tag, or a non-zero tag, we send the response mail
                        if (!"0".equals(responseRequested)) {
                            sendMeetingResponseMail(meetingInfo, response);
                        }
                    }
                }
            } else if (resp.isAuthError()) {
                // TODO: Handle this gracefully.
                //throw new EasAuthenticationException();
            } else {
                LogUtils.e(TAG, "Meeting response request failed, code: %d", status);
                throw new IOException();
            }
        } finally {
            resp.close();
        }
    }
}
