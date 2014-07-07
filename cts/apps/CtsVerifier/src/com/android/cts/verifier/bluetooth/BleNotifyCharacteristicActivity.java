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
import android.widget.TextView;
import android.widget.Toast;

public class BleNotifyCharacteristicActivity extends PassFailButtons.Activity {

    private boolean mEnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_notify_characteristic);
        setPassFailButtonClickListeners();
        setInfoResources(R.string.ble_notify_characteristic_name,
                         R.string.ble_notify_characteristic_info, -1);

        mEnable = false;

        ((Button) findViewById(R.id.ble_notify)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mEnable = !mEnable;
                if (mEnable) ((Button) v).setText(getString(R.string.ble_stop_notification));
                else ((Button) v).setText(getString(R.string.ble_begin_notification));

                Intent intent = new Intent(BleNotifyCharacteristicActivity.this,
                                           BleClientService.class);
                intent.putExtra(BleClientService.EXTRA_COMMAND,
                                BleClientService.COMMAND_SET_NOTIFICATION);
                intent.putExtra(BleClientService.EXTRA_BOOL, mEnable);
                startService(intent);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleClientService.BLE_CHARACTERISTIC_CHANGED);
        registerReceiver(onBroadcast, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(onBroadcast);
        mEnable = false;
        Intent intent = new Intent(BleNotifyCharacteristicActivity.this,
                                   BleClientService.class);
        intent.putExtra(BleClientService.EXTRA_COMMAND,
                        BleClientService.COMMAND_SET_NOTIFICATION);
        intent.putExtra(BleClientService.EXTRA_BOOL, mEnable);
        startService(intent);
    }

    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String value = intent.getStringExtra(BleClientService.EXTRA_CHARACTERISTIC_VALUE);
            ((TextView) findViewById(R.id.ble_notify_text)).setText(value);
        }
    };
}