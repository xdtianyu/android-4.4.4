package com.android.cts.verifier.nfc.hce;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;

import com.android.cts.verifier.R;
import com.android.cts.verifier.nfc.NfcDialogs;

@TargetApi(19)
public class DualPaymentEmulatorActivity extends BaseEmulatorActivity implements OnClickListener {
    final static int STATE_IDLE = 0;
    final static int STATE_SERVICE1_SETTING_UP = 1;
    final static int STATE_SERVICE2_SETTING_UP = 2;
    final static int STATE_MAKING_SERVICE2_DEFAULT = 3;

    boolean mReceiverRegistered = false;
    int mState = STATE_IDLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pass_fail_text);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);
        mState = STATE_SERVICE2_SETTING_UP;
        setupServices(this, PaymentService2.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    void onServicesSetup(boolean result) {
        if (mState == STATE_SERVICE2_SETTING_UP) {
            mState = STATE_SERVICE1_SETTING_UP;
            setupServices(this, PaymentService1.COMPONENT, PaymentService2.COMPONENT);
            return;
        }
        // Verify HCE service 2 is the default
        if (!mCardEmulation.isDefaultServiceForCategory(
                PaymentService2.COMPONENT, CardEmulation.CATEGORY_PAYMENT)) {
            mState = STATE_MAKING_SERVICE2_DEFAULT;
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Note");
            builder.setMessage(R.string.nfc_hce_change_preinstalled_wallet);
            builder.setPositiveButton("OK", this);
            builder.show();
        } else {
            NfcDialogs.createHceTapReaderDialog(this,null).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mReceiverRegistered) {
            unregisterReceiver(mReceiver);
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!mCardEmulation.isDefaultServiceForCategory(
                PaymentService2.COMPONENT, CardEmulation.CATEGORY_PAYMENT)) {
            // Popup dialog-box, fail test
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Test failed.");
            builder.setMessage("PaymentService2 is not the default service according " +
                    "to CardEmulation.getDefaultServiceForCategory(), verify the make " +
                    "default implementation is correct.");
            builder.setPositiveButton("OK", null);
            builder.show();
        } else {
            NfcDialogs.createHceTapReaderDialog(this, null).show();
        }
    }
    public static Intent buildReaderIntent(Context context) {
        Intent readerIntent = new Intent(context, SimpleReaderActivity.class);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_APDUS,
                PaymentService2.APDU_COMMAND_SEQUENCE);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_RESPONSES,
                PaymentService2.APDU_RESPOND_SEQUENCE);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_LABEL,
                context.getString(R.string.nfc_hce_dual_payment_reader));
        return readerIntent;
    }

    @Override
    void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PaymentService2.COMPONENT)) {
            getPassButton().setEnabled(true);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        Intent changeDefault = new Intent(CardEmulation.ACTION_CHANGE_DEFAULT);
        changeDefault.putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT);
        changeDefault.putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, PaymentService2.COMPONENT);
        startActivityForResult(changeDefault, 0);
    }
}
