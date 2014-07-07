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

import java.util.UUID;
import java.util.List;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class BleClientService extends Service {

    public static final boolean DEBUG = true;
    public static final String TAG = "BleClientService";

    public static final int COMMAND_CONNECT = 0;
    public static final int COMMAND_DISCONNECT = 1;
    public static final int COMMAND_DISCOVER_SERVICE = 2;
    public static final int COMMAND_READ_RSSI = 3;
    public static final int COMMAND_WRITE_CHARACTERISTIC = 4;
    public static final int COMMAND_READ_CHARACTERISTIC = 5;
    public static final int COMMAND_WRITE_DESCRIPTOR = 6;
    public static final int COMMAND_READ_DESCRIPTOR = 7;
    public static final int COMMAND_SET_NOTIFICATION = 8;
    public static final int COMMAND_BEGIN_WRITE = 9;
    public static final int COMMAND_EXECUTE_WRITE = 10;
    public static final int COMMAND_ABORT_RELIABLE = 11;

    public static final String BLE_BLUETOOTH_CONNECTED =
            "com.android.cts.verifier.bluetooth.BLE_BLUETOOTH_CONNECTED";
    public static final String BLE_BLUETOOTH_DISCONNECTED =
            "com.android.cts.verifier.bluetooth.BLE_BLUETOOTH_DISCONNECTED";
    public static final String BLE_SERVICES_DISCOVERED =
            "com.android.cts.verifier.bluetooth.BLE_SERVICES_DISCOVERED";
    public static final String BLE_CHARACTERISTIC_READ =
            "com.android.cts.verifier.bluetooth.BLE_CHARACTERISTIC_READ";
    public static final String BLE_CHARACTERISTIC_WRITE =
            "com.android.cts.verifier.bluetooth.BLE_CHARACTERISTIC_WRITE";
    public static final String BLE_CHARACTERISTIC_CHANGED =
            "com.android.cts.verifier.bluetooth.BLE_CHARACTERISTIC_CHANGED";
    public static final String BLE_DESCRIPTOR_READ =
            "com.android.cts.verifier.bluetooth.BLE_DESCRIPTOR_READ";
    public static final String BLE_DESCRIPTOR_WRITE =
            "com.android.cts.verifier.bluetooth.BLE_DESCRIPTOR_WRITE";
    public static final String BLE_RELIABLE_WRITE_COMPLETED =
            "com.android.cts.verifier.bluetooth.BLE_RELIABLE_WRITE_COMPLETED";
    public static final String BLE_READ_REMOTE_RSSI =
            "com.android.cts.verifier.bluetooth.BLE_READ_REMOTE_RSSI";

    public static final String EXTRA_COMMAND =
            "com.android.cts.verifier.bluetooth.EXTRA_COMMAND";
    public static final String EXTRA_WRITE_VALUE =
            "com.android.cts.verifier.bluetooth.EXTRA_WRITE_VALUE";
    public static final String EXTRA_BOOL =
            "com.android.cts.verifier.bluetooth.EXTRA_BOOL";
    public static final String EXTRA_CHARACTERISTIC_VALUE =
            "com.android.cts.verifier.bluetooth.EXTRA_CHARACTERISTIC_VALUE";
    public static final String EXTRA_DESCRIPTOR_VALUE =
            "com.android.cts.verifier.bluetooth.EXTRA_DESCRIPTOR_VALUE";
    public static final String EXTRA_RSSI_VALUE =
            "com.android.cts.verifier.bluetooth.EXTRA_RSSI_VALUE";

    private static final UUID SERVICE_UUID =
            UUID.fromString("00009999-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("00009998-0000-1000-8000-00805f9b34fb");
    private static final UUID UPDATE_CHARACTERISTIC_UUID =
            UUID.fromString("00009997-0000-1000-8000-00805f9b34fb");
    private static final UUID DESCRIPTOR_UUID =
            UUID.fromString("00009996-0000-1000-8000-00805f9b34fb");

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mDevice;
    private BluetoothGatt mBluetoothGatt;
    private Handler mHandler;

    @Override
    public void onCreate() {
        super.onCreate();

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mHandler = new Handler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) handleIntent(intent);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
    }

    private void handleIntent(Intent intent) {
        int command = intent.getIntExtra(EXTRA_COMMAND, -1);
        String address = intent.getStringExtra(BluetoothDevice.EXTRA_DEVICE); // sometimes null
        String writeValue = intent.getStringExtra(EXTRA_WRITE_VALUE); // sometimes null
        boolean enable = intent.getBooleanExtra(EXTRA_BOOL, false);
        BluetoothGattService service;
        BluetoothGattCharacteristic characteristic;
        BluetoothGattDescriptor descriptor;

        switch (command) {
            case COMMAND_CONNECT:
                mDevice = mBluetoothAdapter.getRemoteDevice(address);
                mBluetoothGatt = mDevice.connectGatt(this, false, mGattCallbacks);
                break;
            case COMMAND_DISCONNECT:
                if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
                break;
            case COMMAND_DISCOVER_SERVICE:
                if (mBluetoothGatt != null) mBluetoothGatt.discoverServices();
                break;
            case COMMAND_READ_RSSI:
                if (mBluetoothGatt != null) mBluetoothGatt.readRemoteRssi();
                break;
            case COMMAND_WRITE_CHARACTERISTIC:
                writeCharacteristic(writeValue);
                break;
            case COMMAND_READ_CHARACTERISTIC:
                readCharacteristic();
                break;
            case COMMAND_WRITE_DESCRIPTOR:
                writeDescriptor(writeValue);
                break;
            case COMMAND_READ_DESCRIPTOR:
                readDescriptor();
                break;
            case COMMAND_SET_NOTIFICATION:
                setNotification(enable);
                break;
            case COMMAND_BEGIN_WRITE:
                if (mBluetoothGatt != null) mBluetoothGatt.beginReliableWrite();
                break;
            case COMMAND_EXECUTE_WRITE:
                if (mBluetoothGatt != null) mBluetoothGatt.executeReliableWrite();
                break;
            case COMMAND_ABORT_RELIABLE:
                if (mBluetoothGatt != null) mBluetoothGatt.abortReliableWrite(mDevice);
                break;
            default:
                showMessage("Unrecognized command: " + command);
                break;
        }
    }

    private void writeCharacteristic(String writeValue) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic == null) return;
        characteristic.setValue(writeValue);
        mBluetoothGatt.writeCharacteristic(characteristic);
    }

    private void readCharacteristic() {
        BluetoothGattCharacteristic characteristic = getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic != null) mBluetoothGatt.readCharacteristic(characteristic);
    }

    private void writeDescriptor(String writeValue) {
        BluetoothGattDescriptor descriptor = getDescriptor();
        if (descriptor == null) return;
        descriptor.setValue(writeValue.getBytes());
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    private void readDescriptor() {
        BluetoothGattDescriptor descriptor = getDescriptor();
        if (descriptor != null) mBluetoothGatt.readDescriptor(descriptor);
    }

    private void setNotification(boolean enable) {
        BluetoothGattCharacteristic characteristic = getCharacteristic(UPDATE_CHARACTERISTIC_UUID);
        if (characteristic != null)
            mBluetoothGatt.setCharacteristicNotification(characteristic, enable);
    }

    private void notifyConnected() {
        Intent intent = new Intent(BLE_BLUETOOTH_CONNECTED);
        sendBroadcast(intent);
    }

    private void notifyDisconnected() {
        Intent intent = new Intent(BLE_BLUETOOTH_DISCONNECTED);
        sendBroadcast(intent);
    }

    private void notifyServicesDiscovered() {
        Intent intent = new Intent(BLE_SERVICES_DISCOVERED);
        sendBroadcast(intent);
    }

    private void notifyCharacteristicRead(String value) {
        Intent intent = new Intent(BLE_CHARACTERISTIC_READ);
        intent.putExtra(EXTRA_CHARACTERISTIC_VALUE, value);
        sendBroadcast(intent);
    }

    private void notifyCharacteristicWrite() {
        Intent intent = new Intent(BLE_CHARACTERISTIC_WRITE);
        sendBroadcast(intent);
    }

    private void notifyCharacteristicChanged(String value) {
        Intent intent = new Intent(BLE_CHARACTERISTIC_CHANGED);
        intent.putExtra(EXTRA_CHARACTERISTIC_VALUE, value);
        sendBroadcast(intent);
    }

    private void notifyDescriptorRead(String value) {
        Intent intent = new Intent(BLE_DESCRIPTOR_READ);
        intent.putExtra(EXTRA_DESCRIPTOR_VALUE, value);
        sendBroadcast(intent);
    }

    private void notifyDescriptorWrite() {
        Intent intent = new Intent(BLE_DESCRIPTOR_WRITE);
        sendBroadcast(intent);
    }

    private void notifyReliableWriteCompleted() {
        Intent intent = new Intent(BLE_RELIABLE_WRITE_COMPLETED);
        sendBroadcast(intent);
    }

    private void notifyReadRemoteRssi(int rssi) {
        Intent intent = new Intent(BLE_READ_REMOTE_RSSI);
        intent.putExtra(EXTRA_RSSI_VALUE, rssi);
        sendBroadcast(intent);
    }

    private BluetoothGattService getService() {
        if (mBluetoothGatt == null) return null;

        BluetoothGattService service = mBluetoothGatt.getService(SERVICE_UUID);
        if (service == null) {
            showMessage("Service not found");
            return null;
        }
        return service;
    }

    private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        BluetoothGattService service = getService();
        if (service == null) return null;

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
        if (characteristic == null) {
            showMessage("Characteristic not found");
            return null;
        }
        return characteristic;
    }

    private BluetoothGattDescriptor getDescriptor() {
        BluetoothGattCharacteristic characteristic = getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic == null) return null;

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID);
        if (descriptor == null) {
            showMessage("Descriptor not found");
            return null;
        }
        return descriptor;
    }

    private void showMessage(final String msg) {
        mHandler.post(new Runnable() {
            public void run() {
                Toast.makeText(BleClientService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private final BluetoothGattCallback mGattCallbacks = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (DEBUG) Log.d(TAG, "onConnectionStateChange");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) notifyConnected();
                else if (status == BluetoothProfile.STATE_DISCONNECTED) {
                    notifyDisconnected();
                    showMessage("Bluetooth LE disconnected");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if ((status == BluetoothGatt.GATT_SUCCESS) &&
                (mBluetoothGatt.getService(SERVICE_UUID) != null)) {
                notifyServicesDiscovered();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if ((status == BluetoothGatt.GATT_SUCCESS) &&
                (characteristic.getUuid().equals(CHARACTERISTIC_UUID))) {
                notifyCharacteristicRead(characteristic.getStringValue(0));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (DEBUG) Log.d(TAG, "onCharacteristicWrite: characteristic.val=" + characteristic.getStringValue(0)
                                  + " status=" + status);
            BluetoothGattCharacteristic mCharacteristic = getCharacteristic(CHARACTERISTIC_UUID);
            if ((status == BluetoothGatt.GATT_SUCCESS) &&
                (characteristic.getStringValue(0).equals(mCharacteristic.getStringValue(0)))) {
                notifyCharacteristicWrite();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(UPDATE_CHARACTERISTIC_UUID))
                notifyCharacteristicChanged(characteristic.getStringValue(0));
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                     int status) {
            if ((status == BluetoothGatt.GATT_SUCCESS) &&
                (descriptor.getUuid().equals(DESCRIPTOR_UUID))) {
                notifyDescriptorRead(new String(descriptor.getValue()));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            if ((status == BluetoothGatt.GATT_SUCCESS) &&
                (descriptor.getUuid().equals(DESCRIPTOR_UUID))) {
                notifyDescriptorWrite();
            }
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) notifyReliableWriteCompleted();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) notifyReadRemoteRssi(rssi);
        }
    };
}