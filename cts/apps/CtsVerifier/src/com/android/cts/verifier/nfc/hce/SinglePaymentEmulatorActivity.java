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
public class SinglePaymentEmulatorActivity extends BaseEmulatorActivity implements OnClickListener {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pass_fail_text);
        setPassFailButtonClickListeners();
        getPassButton().setEnabled(false);
        setupServices(this, PaymentService1.COMPONENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    void onServicesSetup(boolean result) {
        // Verify HCE service 1 is the default
        if (!mCardEmulation.isDefaultServiceForCategory(
                PaymentService1.COMPONENT, CardEmulation.CATEGORY_PAYMENT)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Note");
            builder.setMessage(R.string.nfc_hce_change_preinstalled_wallet);
            builder.setPositiveButton("OK", this);
            builder.show();
        } else {
	        NfcDialogs.createHceTapReaderDialog(this, null).show();
        }
    }

    public static Intent buildReaderIntent(Context context) {
        Intent readerIntent = new Intent(context, SimpleReaderActivity.class);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_APDUS,
                PaymentService1.APDU_COMMAND_SEQUENCE);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_RESPONSES,
                PaymentService1.APDU_RESPOND_SEQUENCE);
        readerIntent.putExtra(SimpleReaderActivity.EXTRA_LABEL,
                context.getString(R.string.nfc_hce_single_payment_reader));
        return readerIntent;
    }

    @Override
    void onApduSequenceComplete(ComponentName component, long duration) {
        if (component.equals(PaymentService1.COMPONENT)) {
            getPassButton().setEnabled(true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!mCardEmulation.isDefaultServiceForCategory(
                PaymentService1.COMPONENT, CardEmulation.CATEGORY_PAYMENT)) {
            // Popup dialog-box, fail test
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Test failed.");
            builder.setMessage("PaymentService1 is not the default service according " +
                    "to CardEmulation.getDefaultServiceForCategory(), verify the make " +
                    "default implementation is correct.");
            builder.setPositiveButton("OK", null);
            builder.show();
        } else {
            NfcDialogs.createHceTapReaderDialog(this, null).show();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        Intent changeDefault = new Intent(CardEmulation.ACTION_CHANGE_DEFAULT);
        changeDefault.putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT);
        changeDefault.putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, PaymentService1.COMPONENT);
        startActivityForResult(changeDefault, 0);
    }
}
