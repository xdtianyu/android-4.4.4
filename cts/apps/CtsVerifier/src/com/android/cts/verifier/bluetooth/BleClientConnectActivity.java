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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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

public class BleClientConnectActivity extends PassFailButtons.Activity {

    private EditText mEditText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_client_connect);
        setPassFailButtonClickListeners();
        setInfoResources(R.string.ble_client_connect_name,
                         R.string.ble_client_send_connect_info, -1);
        getPassButton().setEnabled(false);

        mEditText = (EditText) findViewById(R.id.ble_address);

        ((Button) findViewById(R.id.ble_connect)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String address = mEditText.getText().toString();
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    showMessage("Invalid bluetooth address.");
                } else {
                    Intent intent = new Intent(BleClientConnectActivity.this,
                                               BleClientService.class);
                    intent.putExtra(BleClientService.EXTRA_COMMAND,
                                    BleClientService.COMMAND_CONNECT);
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, address);
                    startService(intent);
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleClientService.BLE_BLUETOOTH_CONNECTED);
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
            showMessage("Bluetooth LE connected");
            getPassButton().setEnabled(true);
        }
    };
}