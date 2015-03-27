package com.aleph.mtk.btchannel;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.WifiManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;


import com.whitebyte.wifihotspotutils.WifiApManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends Activity implements View.OnClickListener{

    private boolean start;
    private boolean isServer=true;
    private ArrayList<String> devices;
    private BroadcastReceiver mReceiver;
    public HandoffServer hserver;
    public HandoffClient hclient;

    //BT Manager
    private BluetoothAdapter btadapter;

    //Wi-Fi
    //public WifiP2pManager p2pmanager;
    //public Channel mChannel;
    //public WidiReceiver wReceiver;
    public WifiManager wifimanager;
    public WifiApManager apmanager;
    public String ssid;
    WifiConfiguration apConfig;

    //Constants
    private static UUID PROXY_UUID;

    private static final String PROXY_NAME = "XT1058";
    private static final int REQ_BT = 777;
    private static final int DISCOVERABLE_DURATION = 180;
    public static int TIMEOUT = 5000;
    public static int MAX_RETRY = 5;

    //GUI
    public Handler mHandler;
    public Button startB;
    public TextView tv;
    public TextView mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        start = false;
        isServer = true; //default is server
        devices = new ArrayList();
        PROXY_UUID = new UUID(2506, 3305);
        hserver = null;

        //Wi-Fi p2p manager
        //p2pmanager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        //mChannel = p2pmanager.initialize(this, getMainLooper(), null);
        //wReceiver = new WidiReceiver(p2pmanager, mChannel, this);
        wifimanager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        apmanager = new WifiApManager(this);

        //enable Wi-fi AP
        if(!apmanager.isWifiApEnabled())
            apmanager.setWifiApEnabled(null, true);
        apConfig = apmanager.getWifiApConfiguration();


        //get bt adapter
        //btmanager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        //btadapter = btmanager.getAdapter();
        btadapter = BluetoothAdapter.getDefaultAdapter();

        //initiate UI
        mode = (TextView) findViewById(R.id.text1);
        startB = (Button) findViewById(R.id.button1);
        startB.setOnClickListener(this);
        tv = (TextView)findViewById(R.id.text2);

        mHandler = new Handler(){
            public void handleMessage(Message msg){

                String tmp = msg.getData().getString("status");
                //Control buttons
                if(tmp!=null && tmp.equalsIgnoreCase("stopped")){

                    startB.setText(R.string.button_start);
                    start=false;

                }else {
                    updateUI(msg.getData().getString("data") + "\n");
                }
                super.handleMessage(msg);
            }
        };

        // Create a BroadcastReceiver for BT ACTION_FOUND
        mReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // When discovery finds a device
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {

                    // Get the BluetoothDevice object from the Intent
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                    //Try to connect proxy service
                    connectProxy(device);
                }
            }
        };
    }

    public void onClick(View v){
        if(!start){

            startB.setText(R.string.button_stop);
            start=true;

            if(isServer) startBroadcast();
            else startScan();

        }else{

            startB.setText(R.string.button_start);
            start=false;

            stopThreads();
        }
    }

    private void updateUI(String msg){
        Log.v("BTChannel", msg);
        tv.append(msg + '\n');
    }

    /****************** Start Handoff Server *******************/
    private void startBroadcast(){

        Intent enableBtDiscover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        enableBtDiscover.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        startActivityForResult(enableBtDiscover, REQ_BT);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        if(requestCode==REQ_BT) {
            if (resultCode == Activity.RESULT_CANCELED) {
                updateUI("onActivityResult: BT cannot be enabled.");
                return;
            }
            updateUI("onActivityResult: BT enabled.");

            //Get ip ap addr for the negotiation progress
            //WifiInfo info = wifimanager.getConnectionInfo();
            //int tmp = info.getIpAddress();
            //ipaddr = String.format("%d.%d.%d.%d", (tmp & 0xff), (tmp >> 8 & 0xff), (tmp >> 16 & 0xff), (tmp >> 24 & 0xff));

            hserver = new HandoffServer(this.mHandler, btadapter, apConfig, PROXY_UUID);
            updateUI("onActivityResult: New handoff server thread");

            hserver.start();
        }
    }
    /********************* End Handoff Server *******************/

    /****************** Start Handoff Client *******************/
    private void startScan(){
        if(!btadapter.isEnabled()){
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQ_BT);
        }

        // Find paired devices
        Set<BluetoothDevice> pairedDevices = btadapter.getBondedDevices();
        updateUI("startScan: ---- List of paired devices ----");

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                devices.add(device.getName() + "|" + device.getAddress());
                updateUI("startScan: " + device.getName() + " | " + device.getAddress());

                //Try to connect proxy service
                connectProxy(device);
            }
        }else{//If no bonded device, start scan...

            btadapter.startDiscovery();
            // Register the BroadcastReceiver
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter); // Don't forget to unregister during onDestroy

            updateUI("startScan: No paired device. Start scan...");
        }
    }

    private void connectProxy(BluetoothDevice device){
        //fixed server now
        if(device.getName().equalsIgnoreCase(PROXY_NAME)){

            hclient = new HandoffClient(mHandler, device, btadapter, apmanager, wifimanager, PROXY_UUID);
            System.out.println("Main: New handoff client thread");

            hclient.start();
        }

    }
    /********************** End Handoff Client ***********************/

    /********************** Other Functions ***********************/

    private void stopThreads(){
        if(isServer) {
            if (hserver != null) hserver.cancel();

        }else{
            if(hclient!=null) hclient.cancel();
            tryUnregisterBTDiscover();

        }
        //btadapter.disable();
        updateUI("Main: threads stopped");
    }

    private void tryUnregisterBTDiscover(){
        try {
            unregisterReceiver(mReceiver);
        }catch(IllegalArgumentException e){
            updateUI("tryUnregisterBTDiscover: receiver not registered");
        }
    }


    /**************** Override methods **************/
    protected void onDestroy(){
        if(!isServer)
            tryUnregisterBTDiscover();
        if(apmanager.isWifiApEnabled()) apmanager.setWifiApEnabled(null, false);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_server) {
            isServer = true;
            mode.setText(R.string.mode_server);

            //startBroadcast();
            return true;
        }else if(id== R.id.action_client){
            isServer = false;
            mode.setText(R.string.mode_client);

            //startScan();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }




}
