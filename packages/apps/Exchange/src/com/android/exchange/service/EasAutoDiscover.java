package com.android.exchange.service;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;

import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.exchange.Eas;
import com.android.exchange.EasResponse;
import com.android.mail.utils.LogUtils;

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.cert.CertificateException;

/**
 * Performs Autodiscover for Exchange servers. This feature tries to find all the configuration
 * options needed based on just a username and password.
 */
public class EasAutoDiscover extends EasServerConnection {
    private static final String TAG = Eas.LOG_TAG;

    private static final String AUTO_DISCOVER_SCHEMA_PREFIX =
        "http://schemas.microsoft.com/exchange/autodiscover/mobilesync/";
    private static final String AUTO_DISCOVER_PAGE = "/autodiscover/autodiscover.xml";

    // Set of string constants for parsing the autodiscover response.
    // TODO: Merge this into Tags.java? It's not quite the same but conceptually belongs there.
    private static final String ELEMENT_NAME_SERVER = "Server";
    private static final String ELEMENT_NAME_TYPE = "Type";
    private static final String ELEMENT_NAME_MOBILE_SYNC = "MobileSync";
    private static final String ELEMENT_NAME_URL = "Url";
    private static final String ELEMENT_NAME_SETTINGS = "Settings";
    private static final String ELEMENT_NAME_ACTION = "Action";
    private static final String ELEMENT_NAME_ERROR = "Error";
    private static final String ELEMENT_NAME_REDIRECT = "Redirect";
    private static final String ELEMENT_NAME_USER = "User";
    private static final String ELEMENT_NAME_EMAIL_ADDRESS = "EMailAddress";
    private static final String ELEMENT_NAME_DISPLAY_NAME = "DisplayName";
    private static final String ELEMENT_NAME_RESPONSE = "Response";
    private static final String ELEMENT_NAME_AUTODISCOVER = "Autodiscover";

    public EasAutoDiscover(final Context context, final String username, final String password) {
        super(context, new Account(), new HostAuth());
        mHostAuth.mLogin = username;
        mHostAuth.mPassword = password;
        mHostAuth.mFlags = HostAuth.FLAG_AUTHENTICATE | HostAuth.FLAG_SSL;
        mHostAuth.mPort = 443;
    }

    /**
     * Do all the work of autodiscovery.
     * @return A {@link Bundle} with the host information if autodiscovery succeeded. If we failed
     *     due to an authentication failure, we return a {@link Bundle} with no host info but with
     *     an appropriate error code. Otherwise, we return null.
     */
    public Bundle doAutodiscover() {
        final String domain = getDomain();
        if (domain == null) {
            return null;
        }

        final StringEntity entity = buildRequestEntity();
        if (entity == null) {
            return null;
        }
        try {
            final HttpPost post = makePost("https://" + domain + AUTO_DISCOVER_PAGE, entity,
                    "text/xml", false);
            final EasResponse resp = getResponse(post, domain);
            if (resp == null) {
                return null;
            }

            try {
                // resp is either an authentication error, or a good response.
                final int code = resp.getStatus();
                if (code == HttpStatus.SC_UNAUTHORIZED) {
                    final Bundle bundle = new Bundle(1);
                    bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                            MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED);
                    return bundle;
                } else {
                    final HostAuth hostAuth = parseAutodiscover(resp);
                    if (hostAuth != null) {
                        // Fill in the rest of the HostAuth
                        // We use the user name and password that were successful during
                        // the autodiscover process
                        hostAuth.mLogin = mHostAuth.mLogin;
                        hostAuth.mPassword = mHostAuth.mPassword;
                        // Note: there is no way we can auto-discover the proper client
                        // SSL certificate to use, if one is needed.
                        hostAuth.mPort = 443;
                        hostAuth.mProtocol = Eas.PROTOCOL;
                        hostAuth.mFlags = HostAuth.FLAG_SSL | HostAuth.FLAG_AUTHENTICATE;
                        final Bundle bundle = new Bundle(2);
                        bundle.putParcelable(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH,
                                hostAuth);
                        bundle.putInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE,
                                MessagingException.NO_ERROR);
                        return bundle;
                    }
                }
            } finally {
                resp.close();
            }
        } catch (final IllegalArgumentException e) {
            // This happens when the domain is malformatted.
            // TODO: Fix sanitizing of the domain -- we try to in UI but apparently not correctly.
            LogUtils.e(TAG, "ISE with domain: %s", domain);
        }
        return null;
    }

    /**
     * Get the domain of our account.
     * @return The domain of the email address.
     */
    private String getDomain() {
        final int amp = mHostAuth.mLogin.indexOf('@');
        if (amp < 0) {
            return null;
        }
        return mHostAuth.mLogin.substring(amp + 1);
    }

    /**
     * Create the payload of the request.
     * @return A {@link StringEntity} for the request XML.
     */
    private StringEntity buildRequestEntity() {
        try {
            final XmlSerializer s = Xml.newSerializer();
            final ByteArrayOutputStream os = new ByteArrayOutputStream(1024);
            s.setOutput(os, "UTF-8");
            s.startDocument("UTF-8", false);
            s.startTag(null, "Autodiscover");
            s.attribute(null, "xmlns", AUTO_DISCOVER_SCHEMA_PREFIX + "requestschema/2006");
            s.startTag(null, "Request");
            s.startTag(null, "EMailAddress").text(mHostAuth.mLogin).endTag(null, "EMailAddress");
            s.startTag(null, "AcceptableResponseSchema");
            s.text(AUTO_DISCOVER_SCHEMA_PREFIX + "responseschema/2006");
            s.endTag(null, "AcceptableResponseSchema");
            s.endTag(null, "Request");
            s.endTag(null, "Autodiscover");
            s.endDocument();
            return new StringEntity(os.toString());
        } catch (final IOException e) {
            // For all exception types, we can simply punt on autodiscover.
        } catch (final IllegalArgumentException e) {
        } catch (final IllegalStateException e) {
        }

        return null;
    }

    /**
     * Perform all requests necessary and get the server response. If the post fails or is
     * redirected, we alter the post and retry.
     * @param post The initial {@link HttpPost} for this request.
     * @param domain The domain for our account.
     * @return If this request succeeded or has an unrecoverable authentication error, an
     *     {@link EasResponse} with the details. For other errors, we return null.
     */
    private EasResponse getResponse(final HttpPost post, final String domain) {
        EasResponse resp = doPost(post, true);
        if (resp == null) {
            LogUtils.d(TAG, "Error in autodiscover, trying aternate address");
            post.setURI(URI.create("https://autodiscover." + domain + AUTO_DISCOVER_PAGE));
            resp = doPost(post, true);
        }
        return resp;
    }

    /**
     * Perform one attempt to get autodiscover information. Redirection and some authentication
     * errors are handled by recursively calls with modified host information.
     * @param post The {@link HttpPost} for this request.
     * @param canRetry Whether we can retry after an authentication failure.
     * @return If this request succeeded or has an unrecoverable authentication error, an
     *     {@link EasResponse} with the details. For other errors, we return null.
     */
    private EasResponse doPost(final HttpPost post, final boolean canRetry) {
        final EasResponse resp;
        try {
            resp = executePost(post);
        } catch (final IOException e) {
            return null;
        } catch (final CertificateException e) {
            // TODO: Raise this error to the user or something
            return null;
        }

        final int code = resp.getStatus();

        if (resp.isRedirectError()) {
            final String loc = resp.getRedirectAddress();
            if (loc != null && loc.startsWith("http")) {
                LogUtils.d(TAG, "Posting autodiscover to redirect: " + loc);
                redirectHostAuth(loc);
                post.setURI(URI.create(loc));
                return doPost(post, canRetry);
            }
            return null;
        }

        if (code == HttpStatus.SC_UNAUTHORIZED) {
            if (canRetry && mHostAuth.mLogin.contains("@")) {
                // Try again using the bare user name
                final int atSignIndex = mHostAuth.mLogin.indexOf('@');
                mHostAuth.mLogin = mHostAuth.mLogin.substring(0, atSignIndex);
                LogUtils.d(TAG, "401 received; trying username: %s", mHostAuth.mLogin);
                resetAuthorization(post);
                return doPost(post, false);
            }
        } else if (code != HttpStatus.SC_OK) {
            // We'll try the next address if this doesn't work
            LogUtils.d(TAG, "Bad response code when posting autodiscover: %d", code);
            return null;
        }

        return resp;
    }

    /**
     * Parse the Server element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseServer(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        boolean mobileSync = false;
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_SERVER)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_TYPE)) {
                    if (parser.nextText().equals(ELEMENT_NAME_MOBILE_SYNC)) {
                        mobileSync = true;
                    }
                } else if (mobileSync && name.equals(ELEMENT_NAME_URL)) {
                    final String url = parser.nextText();
                    if (url != null) {
                        LogUtils.d(TAG, "Autodiscover URL: %s", url);
                        hostAuth.mAddress = Uri.parse(url).getHost();
                    }
                }
            }
        }
    }

    /**
     * Parse the Settings element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseSettings(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_SETTINGS)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_SERVER)) {
                    parseServer(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the Action element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseAction(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_ACTION)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_ERROR)) {
                    // Should parse the error
                } else if (name.equals(ELEMENT_NAME_REDIRECT)) {
                    LogUtils.d(TAG, "Redirect: " + parser.nextText());
                } else if (name.equals(ELEMENT_NAME_SETTINGS)) {
                    parseSettings(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the User element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseUser(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_USER)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if (name.equals(ELEMENT_NAME_EMAIL_ADDRESS)) {
                    final String addr = parser.nextText();
                    LogUtils.d(TAG, "Autodiscover, email: %s", addr);
                } else if (name.equals(ELEMENT_NAME_DISPLAY_NAME)) {
                    final String dn = parser.nextText();
                    LogUtils.d(TAG, "Autodiscover, user: %s", dn);
                }
            }
        }
    }

    /**
     * Parse the Response element of the server response.
     * @param parser The {@link XmlPullParser}.
     * @param hostAuth The {@link HostAuth} to populate with the results of parsing.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void parseResponse(final XmlPullParser parser, final HostAuth hostAuth)
            throws XmlPullParserException, IOException {
        while (true) {
            final int type = parser.next();
            if (type == XmlPullParser.END_TAG && parser.getName().equals(ELEMENT_NAME_RESPONSE)) {
                break;
            } else if (type == XmlPullParser.START_TAG) {
                final String name = parser.getName();
                if (name.equals(ELEMENT_NAME_USER)) {
                    parseUser(parser, hostAuth);
                } else if (name.equals(ELEMENT_NAME_ACTION)) {
                    parseAction(parser, hostAuth);
                }
            }
        }
    }

    /**
     * Parse the server response for the final {@link HostAuth}.
     * @param resp The {@link EasResponse} from the server.
     * @return The final {@link HostAuth} for this server.
     */
    private static HostAuth parseAutodiscover(final EasResponse resp) {
        // The response to Autodiscover is regular XML (not WBXML)
        try {
            final XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(resp.getInputStream(), "UTF-8");
            if (parser.getEventType() != XmlPullParser.START_DOCUMENT) {
                return null;
            }
            if (parser.next() != XmlPullParser.START_TAG) {
                return null;
            }
            if (!parser.getName().equals(ELEMENT_NAME_AUTODISCOVER)) {
                return null;
            }

            final HostAuth hostAuth = new HostAuth();
            while (true) {
                final int type = parser.nextTag();
                if (type == XmlPullParser.END_TAG && parser.getName()
                        .equals(ELEMENT_NAME_AUTODISCOVER)) {
                    break;
                } else if (type == XmlPullParser.START_TAG && parser.getName()
                        .equals(ELEMENT_NAME_RESPONSE)) {
                    parseResponse(parser, hostAuth);
                    // Valid responses will set the address.
                    if (hostAuth.mAddress != null) {
                        return hostAuth;
                    }
                }
            }
        } catch (final XmlPullParserException e) {
            // Parse error.
        } catch (final IOException e) {
            // Error reading parser.
        }
        return null;
    }
}
