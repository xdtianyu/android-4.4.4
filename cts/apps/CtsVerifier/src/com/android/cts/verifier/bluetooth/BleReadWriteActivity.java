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
import android.widget.TextView;
import android.widget.Toast;

class BleReadWriteActivity extends PassFailButtons.Activity {

    static final int CHARACTERISTIC = 0;
    static final int DESCRIPTOR = 1;

    private int mWriteCommand;
    private int mReadCommand;
    private String mWriteFilter;
    private String mReadFilter;
    private String mExtraValue;
    private int mName;
    private EditText mEditText;

    BleReadWriteActivity(int target) {
        if (target == CHARACTERISTIC) {
            mWriteCommand = BleClientService.COMMAND_WRITE_CHARACTERISTIC;
            mReadCommand = BleClientService.COMMAND_READ_CHARACTERISTIC;
            mWriteFilter = BleClientService.BLE_CHARACTERISTIC_WRITE;
            mReadFilter = BleClientService.BLE_CHARACTERISTIC_READ;
            mExtraValue = BleClientService.EXTRA_CHARACTERISTIC_VALUE;
            mName = R.string.ble_client_characteristic_name;
        } else if (target == DESCRIPTOR) {
            mWriteCommand = BleClientService.COMMAND_WRITE_DESCRIPTOR;
            mReadCommand = BleClientService.COMMAND_READ_DESCRIPTOR;
            mWriteFilter = BleClientService.BLE_DESCRIPTOR_WRITE;
            mReadFilter = BleClientService.BLE_DESCRIPTOR_READ;
            mExtraValue = BleClientService.EXTRA_DESCRIPTOR_VALUE;
            mName = R.string.ble_client_descriptor_name;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_client_read_write);
        setPassFailButtonClickListeners();
        setInfoResources(mName, R.string.ble_read_write_info, -1);

        mEditText = (EditText) findViewById(R.id.write_text);

        ((Button) findViewById(R.id.ble_write)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String writeValue = mEditText.getText().toString();
                Intent intent = new Intent(BleReadWriteActivity.this, BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND, mWriteCommand);
                intent.putExtra(BleClientService.EXTRA_WRITE_VALUE, writeValue);
                startService(intent);
                mEditText.setText("");
            }
        });

        ((Button) findViewById(R.id.ble_read)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(BleReadWriteActivity.this, BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND, mReadCommand);
                startService(intent);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(mReadFilter);
        filter.addAction(mWriteFilter);
        registerReceiver(onBroadcast, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(onBroadcast);
    }

    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == mWriteFilter)
                showMessage("Write successful callback");
            else if (action == mReadFilter) {
                String value = intent.getStringExtra(mExtraValue);
                ((TextView) findViewById(R.id.read_text)).setText(value);
            }
        }
    };
}