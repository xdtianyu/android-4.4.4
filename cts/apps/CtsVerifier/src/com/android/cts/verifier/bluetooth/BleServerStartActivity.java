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

import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class BleServerStartActivity extends PassFailButtons.Activity {

    private List<Test> mTestList;
    private TestAdapter mTestAdapter;
    private int mAllPassed;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_server_start);
        setPassFailButtonClickListeners();
        setInfoResources(R.string.ble_server_start_name,
                         R.string.ble_server_start_info, -1);
        getPassButton().setEnabled(false);

        mTestList = setupTestList();
        mTestAdapter = new TestAdapter(this, mTestList);

        ListView listView = (ListView) findViewById(R.id.ble_server_tests);
        listView.setAdapter(mTestAdapter);

        mAllPassed = 0;
        startService(new Intent(this, BleServerService.class));
    }

    @Override
    public void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BleServerService.BLE_SERVICE_ADDED);
        filter.addAction(BleServerService.BLE_SERVER_CONNECTED);
        filter.addAction(BleServerService.BLE_CHARACTERISTIC_READ_REQUEST);
        filter.addAction(BleServerService.BLE_CHARACTERISTIC_WRITE_REQUEST);
        filter.addAction(BleServerService.BLE_DESCRIPTOR_READ_REQUEST);
        filter.addAction(BleServerService.BLE_DESCRIPTOR_WRITE_REQUEST);
        filter.addAction(BleServerService.BLE_EXECUTE_WRITE);
        filter.addAction(BleServerService.BLE_SERVER_DISCONNECTED);
        registerReceiver(onBroadcast, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(onBroadcast);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService(new Intent(this, BleServerService.class));
    }

    private List<Test> setupTestList() {
        ArrayList<Test> testList = new ArrayList<Test>();
        testList.add(new Test(R.string.ble_server_add_service));
        testList.add(new Test(R.string.ble_server_receiving_connect));
        testList.add(new Test(R.string.ble_server_read_characteristic));
        testList.add(new Test(R.string.ble_server_write_characteristic));
        testList.add(new Test(R.string.ble_server_read_descriptor));
        testList.add(new Test(R.string.ble_server_write_descriptor));
        testList.add(new Test(R.string.ble_server_reliable_write));
        testList.add(new Test(R.string.ble_server_receiving_disconnect));
        return testList;
    }

    class Test {
        private boolean passed;
        private int instructions;

        private Test(int instructions) {
            passed = false;
            this.instructions = instructions;
        }
    }

    private BroadcastReceiver onBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == BleServerService.BLE_SERVICE_ADDED) {
                mTestList.get(0).passed = true;
                mAllPassed |= 0x01;
            } else if (action == BleServerService.BLE_SERVER_CONNECTED) {
                mTestList.get(1).passed = true;
                mAllPassed |= 0x02;
            } else if (action == BleServerService.BLE_CHARACTERISTIC_READ_REQUEST) {
                mTestList.get(2).passed = true;
                mAllPassed |= 0x04;
            } else if (action == BleServerService.BLE_CHARACTERISTIC_WRITE_REQUEST) {
                mTestList.get(3).passed = true;
                mAllPassed |= 0x08;
            } else if (action == BleServerService.BLE_DESCRIPTOR_READ_REQUEST) {
                mTestList.get(4).passed = true;
                mAllPassed |= 0x10;
            } else if (action == BleServerService.BLE_DESCRIPTOR_WRITE_REQUEST) {
                mTestList.get(5).passed = true;
                mAllPassed |= 0x20;
            } else if (action == BleServerService.BLE_EXECUTE_WRITE) {
                mTestList.get(6).passed = true;
                mAllPassed |= 0x40;
            } else if (action == BleServerService.BLE_SERVER_DISCONNECTED) {
                mTestList.get(7).passed = true;
                mAllPassed |= 0x80;
            }
            mTestAdapter.notifyDataSetChanged();
            if (mAllPassed == 0xFF) getPassButton().setEnabled(true);
        }
    };

    class TestAdapter extends BaseAdapter {
        Context context;
        List<Test> tests;
        LayoutInflater inflater;

        public TestAdapter(Context context, List<Test> tests) {
            this.context = context;
            inflater = LayoutInflater.from(context);
            this.tests = tests;
        }

        @Override
        public int getCount() {
            return tests.size();
        }

        @Override
        public Object getItem(int position) {
            return tests.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewGroup vg;

            if (convertView != null) {
                vg = (ViewGroup) convertView;
            } else {
                vg = (ViewGroup) inflater.inflate(R.layout.ble_server_start_item, null);
            }

            Test test = tests.get(position);
            if (test.passed) {
                ((ImageView) vg.findViewById(R.id.status)).setImageResource(R.drawable.fs_good);
            } else {
                ((ImageView) vg.findViewById(R.id.status)).
                                setImageResource(R.drawable.fs_indeterminate);
            }
            ((TextView) vg.findViewById(R.id.instructions)).setText(test.instructions);

            return vg;
        }
    }
}