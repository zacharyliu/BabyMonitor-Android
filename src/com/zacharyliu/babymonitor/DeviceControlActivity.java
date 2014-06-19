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

package com.zacharyliu.babymonitor;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.*;
import it.gmariotti.cardslib.library.internal.Card;
import it.gmariotti.cardslib.library.internal.CardHeader;
import it.gmariotti.cardslib.library.view.CardView;

import java.util.ArrayList;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private final static String TAG = DeviceControlActivity.class.getSimpleName();
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private boolean mFoundDevice = false;
    private ProgressDialog mProgressDialog;
    private Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private BluetoothDevice mDevice;
    private Card tcard;
    private CardView tcardView;
    private Card acard;
    private CardView acardView;
    private final String TARGET_DEVICE_NAME = "Baby Monitor";
    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";
    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    Log.d(TAG, "Found new Bluetooth LE device " + device.getName());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            addDevice(device);
                        }
                    });
                }
            };

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.

            mProgressDialog.setMessage("Connecting to device");
            mBluetoothLeService.connect(mDevice.getAddress());
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;

                mProgressDialog.setMessage("Connected");
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mProgressDialog.cancel();
                    }
                }, 1000);

                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                restart();
            } else if (BluetoothLeService.ACTION_THERMOMETER.equals(action)) {
                tcardtext.setText(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ACTION_ACCELEROMETER.equals(action)) {
                acardtext.setText(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };
    private TextView tcardtext;
    private TextView acardtext;

    private void restart() {
        Intent intent = getIntent();
        exit();
        startActivity(intent);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_ACCELEROMETER);
        intentFilter.addAction(BluetoothLeService.ACTION_THERMOMETER);
        return intentFilter;
    }

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mDataField.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        mHandler = new Handler();

        // Sets up UI references.
        mConnectionState = (TextView) findViewById(R.id.connection_state);

        //Create a Card
        tcard = new Card(DeviceControlActivity.this, R.layout.temperature);

        //Create a CardHeader
        CardHeader theader = new CardHeader(DeviceControlActivity.this);
        theader.setTitle("Oral Temperature");

        //Add Header to card
        tcard.addCardHeader(theader);

        tcardView = (CardView) findViewById(R.id.carddemo);
        tcardView.setCard(tcard);

        tcardtext = (TextView) findViewById(R.id.temperature_text);
        tcardtext.setText("...");

        //Create a Card
        acard = new Card(DeviceControlActivity.this, R.layout.acceleration);

        //Create a CardHeader
        CardHeader aheader = new CardHeader(DeviceControlActivity.this);
        aheader.setTitle("Pacifier Orientation");

        //Add Header to card
        acard.addCardHeader(aheader);

        acardView = (CardView) findViewById(R.id.carddemo2);
        acardView.setCard(acard);

        acardtext = (TextView) findViewById(R.id.acceleration_text);
        acardtext.setText("Loading...");

        getActionBar().setTitle("Baby Monitor");

        mProgressDialog = ProgressDialog.show(DeviceControlActivity.this, "", "Loading", true);

        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            Log.d(TAG, "Now scanning");
            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            Log.d(TAG, "Scanning stopped");
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }

    private void addDevice(BluetoothDevice device) {
//        Toast.makeText(DeviceControlActivity.this, "Found device " + device.getName(), Toast.LENGTH_SHORT).show();

        if (device.getName().equals(TARGET_DEVICE_NAME)) {
            mFoundDevice = true;
            mDevice = device;

            mProgressDialog.setMessage("Found device, now connecting");

            // now that we found the device, start the background service
            Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
            startService(gattServiceIntent);
            bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "onResume");
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }

        mProgressDialog.setMessage("Looking for baby monitor");

        scanLeDevice(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_exit:
                exit();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exit() {
        mBluetoothLeService.disconnect();
        mBluetoothLeService.exit();
        finish();
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }


}
