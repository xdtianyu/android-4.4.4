/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.cts.verifier.bluetooth;

import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class BleReliableWriteActivity extends PassFailButtons.Activity {

    EditText mEditText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_reliable_write);
        setPassFailButtonClickListeners();
        setInfoResources(R.string.ble_reliable_write_name, R.string.ble_reliable_write_info, -1);
        getPassButton().setEnabled(false);
        ((Button) findViewById(R.id.ble_execute)).setEnabled(false);

        mEditText = (EditText) findViewById(R.id.write_text);

        ((Button) findViewById(R.id.ble_begin)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(BleReliableWriteActivity.this, BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND,
                                BleClientService.COMMAND_BEGIN_WRITE);
                startService(intent);
            }
        });

        ((Button) findViewById(R.id.ble_write)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String writeValue = mEditText.getText().toString();
                Intent intent = new Intent(BleReliableWriteActivity.this, BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND,
                                BleClientService.COMMAND_WRITE_CHARACTERISTIC);
                intent.putExtra(BleClientService.EXTRA_WRITE_VALUE, writeValue);
                startService(intent);
                mEditText.setText("");
            }
        });

        ((Button) findViewById(R.id.ble_execute)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(BleReliableWriteActivity.this, BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND,
                                BleClientService.COMMAND_EXECUTE_WRITE);
                startService(intent);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleClientService.BLE_CHARACTERISTIC_WRITE);
        filter.addAction(BleClientService.BLE_RELIABLE_WRITE_COMPLETED);
        registerReceiver(onBroadcast, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(onBroadcast);
        Intent intent = new Intent(this, BleClientService.class);
        intent.putExtra(BleClientService.EXTRA_COMMAND, BleClientService.COMMAND_ABORT_RELIABLE);
        startService(intent);
    }

    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == BleClientService.BLE_CHARACTERISTIC_WRITE) {
                showMessage("Write value verified.");
                ((Button) findViewById(R.id.ble_execute)).setEnabled(true);
            } else if (action == BleClientService.BLE_RELIABLE_WRITE_COMPLETED) {
                showMessage("Reliable write completed.");
                getPassButton().setEnabled(true);
            }
        }
    };
}