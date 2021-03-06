package com.aleph.mtk.btchannel;

import android.app.AlarmManager;
import android.app.ListActivity;
import android.app.PendingIntent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
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
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.os.Handler;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
//import com.aleph.mtk.proxyservice.ProxyService;
import com.whitebyte.wifihotspotutils.WifiApManager;

import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends ListActivity implements View.OnClickListener, MyLogger{

    private boolean start;
    private boolean isServer=true;
    private boolean APmode;

    private ArrayList<String> devices;
    private BroadcastReceiver mReceiver;
    private BroadcastReceiver ctrlReceiver;
    public HandoffServer hserver;
    public HandoffClient hclient;
    public HandoffImpl handoff;

    private InfoCenter infocenter;

    //BT Manager
    private BluetoothAdapter btadapter;

    //Wi-Fi
    //public WifiP2pManager p2pmanager;
    //public Channel mChannel;
    //public WidiReceiver wReceiver;
    public WifiManager wifimanager;
    public WifiApManager apmanager;
    //public String ssid;
    //WifiConfiguration apConfig;


    //Static
    private static UUID PROXY_UUID;

    //Constants
    private static final String TAG = "BTChannel";
    private static final String PROXY_NAME = "proxy";
    private static final int REQ_BT_SERVER = 777;
    private static final int REQ_BT_CLIENT = 777;
    private static final int DISCOVERABLE_DURATION = 180;
    public static int TIMEOUT = 1000;
    public static int MAX_RETRY = 10;
    public static int UPDATE_PERIOD = 1; //1 SEC
    public final static String ACTION_START = "com.mtk.aleph.btchannel.START";
    public final static String ACTION_STOP = "com.mtk.aleph.btchannel.STOP";
    public final static String ACTION_TURBO_OFF = "com.mtk.aleph.btchannel.TURBO_OFF";

    public static final String MSG_COMMAND = "status";
    public static final String MSG_PRINT = "data";
    public static final String CMD_CLEARLIST = "clear-list";
    public static final String CMD_UPDATELIST = "update-list";
    public static final String CMD_STOP = "stopped";

    //GUI
    public Handler mHandler;
    public Button startB;
    public TextView tv;
    public TextView mode;
    public TextView listtitle;
    public ClientListAdapter listAdapter;
    public UIUpdateThread listUpdater;
    public ScrollView scroll;

    public class APFixThread extends Thread{
        public void run() {

            while(!apmanager.isWifiApEnabled()){} //simple pin lock
            try {
                Process suProcess = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());
                os.writeBytes("route add -net 224.0.0.0 netmask 224.0.0.0 dev ap0" + "\n");
                os.flush();

                os.writeBytes("exit\n");
                os.flush();

                int res = suProcess.waitFor();
                Log.v("ROOT", "change route finished");

            } catch (IOException e) {
                Log.e("ROOT", "cannot get root access, try to change directly");
                try {
                    Process suProcess = Runtime.getRuntime().exec("sh");
                    DataOutputStream os = new DataOutputStream(suProcess.getOutputStream());
                    os.writeBytes("route add -net 224.0.0.0 netmask 224.0.0.0 dev ap0" + "\n");
                    os.flush();

                    os.writeBytes("exit\n");
                    os.flush();

                    int res = suProcess.waitFor();
                    Log.v("ROOT", "change route finished");

                }catch (IOException e1) {

                }catch (InterruptedException e2) {
                    e2.printStackTrace();
                }
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            runOnUiThread( new Thread(new Runnable() {
                    @Override
                    public void run() {
                        notifyOICClients(HandoffImpl.INIT_OIC_STACK);
                        if(startB!=null)startB.setEnabled(true);
                    }
                })
            );

        }
    }

    private static MainActivity instance = null;

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //for singleton
        instance = this;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        start = false;
        isServer = true; //default is server
        APmode = true;

        devices = new ArrayList();
        PROXY_UUID = new UUID(2506, 3305);
        hserver = null;

        //Wi-Fi manager
        wifimanager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        apmanager = new WifiApManager(this);

        //enable Wi-fi AP
        if(!apmanager.isWifiApEnabled())
            apmanager.setWifiApEnabled(null, true);
        //apConfig = apmanager.getWifiApConfiguration();

        //get bt adapter
        //btmanager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        //btadapter = btmanager.getAdapter();
        btadapter = BluetoothAdapter.getDefaultAdapter();

        handoff = new HandoffImpl(this, apmanager, wifimanager, this);

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

        ctrlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(ACTION_START)){
                    Log.v("BTChannel", "Receive msg action_start");
                    if(!start){
                        Util.sleep(10);
                        startB.setText(R.string.button_stop);
                        start=true;

                        if(isServer) startBroadcast();
                        else startScan();

                    }
                }else if (ACTION_STOP.equals(action)){
                    Log.v("BTChannel", "Receive msg action_stop");
                    if(start){
                        startB.setText(R.string.button_start);
                        start=false;
                        stopThreads();
                    }
                }else if(ACTION_TURBO_OFF.equals(action)){
                    if(listUpdater!=null)listUpdater.resetPeriod();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_START);
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_TURBO_OFF);
        registerReceiver(ctrlReceiver, filter);

        //GUI init
        mode = (TextView) findViewById(R.id.text1);
        startB = (Button) findViewById(R.id.button1);
        startB.setOnClickListener(this);
        startB.setEnabled(false);
        tv = (TextView)findViewById(R.id.text2);
        listtitle = (TextView) findViewById(R.id.listtitle);
        scroll = (ScrollView) findViewById(R.id.scrollView);

        mHandler = new Handler(){
            public void handleMessage(Message msg){

                String tmp = msg.getData().getString(MSG_COMMAND);
                //Control buttons
                if(tmp!=null){
                    if(tmp.equalsIgnoreCase(CMD_STOP)){
                        startB.setText(R.string.button_start);
                        start = false;
                    }else if (tmp.equalsIgnoreCase(CMD_CLEARLIST)){
                        //if(listUpdater!=null) listUpdater.cancel();
                        clearList();
                    }else if(tmp.equals(CMD_UPDATELIST)){
                        updateListTurbo();
                    }
                }else {
                    printui(msg.getData().getString(MSG_PRINT) + "\n");
                }
                super.handleMessage(msg);
            }
        };

        //list view & update thread
        listAdapter = new ClientListAdapter(this);
        setListAdapter(listAdapter);

        //info center
        infocenter = new InfoCenter(this.getApplicationContext(), apmanager, btadapter);

        // Fix ap route
        new APFixThread().start();
    }

    protected void onDestroy(){

        //Close list updater daemon
        listUpdater.cancel();

        //Close BT discovery
        if(!isServer)
            tryUnregisterBTDiscover();
        unregisterReceiver(ctrlReceiver);

        // Enable Wi-fi
        if(apmanager.isWifiApEnabled()) apmanager.setWifiApEnabled(null, false);

        // Stop server/client threads
        stopThreads();

        // WORKAROUND: clear buffered OcResource
        HandoffServer.clearBin();

        super.onDestroy();
    }

    public void onStop(){
        if(listUpdater!=null)listUpdater.cancel();
        super.onStop();
    }

    public void onStart(){
        super.onStart();
        listUpdater = new UIUpdateThread(apmanager, listAdapter, UPDATE_PERIOD);
        listUpdater.start();

        infocenter.updateInfo(); //update for the first time
    }

    /*********** UI & BT scan/discover result handler**********/
    public void onClick(View v){
        if(!start){

            startB.setText(R.string.button_stop);
            start=true;

            if(isServer) startBroadcast();
            else{
                //pause(10);
                startScan();
            }

        }else{
            //startB.setText(R.string.button_start);
            //start=false;
            stopThreads();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        if (resultCode == Activity.RESULT_CANCELED) {
            printui("onActivityResult: BT cannot be enabled.");
            return;
        }

        //if(requestCode==REQ_BT_SERVER) {
        if(isServer) {
            printui("onActivityResult: BT enabled.");
            startServer();

            //}else if(requestCode == REQ_BT_CLIENT){
        }else{
            printui("onActivityResult: BT enabled.");
            startClient();
        }
    }



    /****************** Start Handoff Server *******************/
    private void startBroadcast(){

        Intent enableBtDiscover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        enableBtDiscover.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        startActivityForResult(enableBtDiscover, REQ_BT_SERVER);

    }

    private void startServer(){
        //Get ip ap addr for the negotiation progress
        //WifiInfo info = wifimanager.getConnectionInfo();
        //int tmp = info.getIpAddress();
        //ipaddr = String.format("%d.%d.%d.%d", (tmp & 0xff), (tmp >> 8 & 0xff), (tmp >> 16 & 0xff), (tmp >> 24 & 0xff));

        hserver = new HandoffServer(this, this.mHandler, handoff, btadapter, infocenter, PROXY_UUID, APmode);
        printui("onActivityResult: New handoff server thread");

        hserver.start();
    }



    /****************** Start Handoff Client *******************/
    private void startScan(){
        if(!btadapter.isEnabled()){
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQ_BT_CLIENT);
        }else
            startClient();
    }

    private void startClient(){
        // Find paired devices
        Set<BluetoothDevice> pairedDevices = btadapter.getBondedDevices();
        printui("startScan: ---- List of paired devices ----");

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                devices.add(device.getName() + "|" + device.getAddress());
                printui("startScan: " + device.getName() + " | " + device.getAddress());

                //Try to connect proxy service
                boolean res = connectProxy(device);
                if(res) break;

            }
        }else{//If no bonded device, start scan...

            btadapter.startDiscovery();
            // Register the BroadcastReceiver
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter); // Don't forget to unregister during onDestroy

            printui("startScan: No paired device. Start scan...");
        }
    }

    private boolean connectProxy(BluetoothDevice device){
        //fixed server now
        if(device.getName().contains(PROXY_NAME)){

            printui("try to connect proxy " + device.getName());
            hclient = new HandoffClient(this, mHandler, handoff, device, infocenter, PROXY_UUID, APmode);

            hclient.start();

            // Cancel discovery because it will slow down the connection
            btadapter.cancelDiscovery();
            return true;
        }
        return false;

    }


    /********************** Helper Functions ***********************/

    private void stopThreads(){
        //if(isServer) {
            if (hserver != null) hserver.cancel();

        //}else{
            if(hclient!=null) hclient.cancel();
            tryUnregisterBTDiscover();
        //}
        //btadapter.disable();
        startB.setText(R.string.button_start);
        start=false;
        printui("Main: threads stopped");
    }

    public void notifyOICClients(String action){
        Log.d(TAG, "Main: Notify all Iotivity clients ..." + action);
        Intent notify = new Intent(action);
        sendBroadcast(notify);
    }

    private void notifyOICClientsDelay(String action, int delaySec){
        Log.d(TAG, "notify OIC Clients, action: " + action);
        long trigger = SystemClock.elapsedRealtime() + delaySec * 1000;

        Intent notify = new Intent(action);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, notify, PendingIntent.FLAG_ONE_SHOT);

        AlarmManager am  = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
    }

    private void tryUnregisterBTDiscover(){
        try {
            unregisterReceiver(mReceiver);
        }catch(IllegalArgumentException e){
            printui("tryUnregisterBTDiscover: receiver not registered");
        }
    }
    
    public void printui(final String msg){
        runOnUiThread(new Thread(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, msg);
                SimpleDateFormat sdfDate = new SimpleDateFormat("(yyyy-MM-dd HH:mm:ss)");//dd/MM/yyyy
                Date now = new Date();
                String strDate = sdfDate.format(now);
                tv.append(strDate + msg + '\n');
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        }));
    }

    private void updateListTurbo(){
        Log.v(TAG, "updateListTurbo: update ap client list faster");
        listUpdater.cancel();
        listUpdater = new UIUpdateThread(apmanager, listAdapter, 1);
        listUpdater.start();

        Intent it = new Intent(ACTION_TURBO_OFF);
        PendingIntent pit = PendingIntent.getBroadcast(this, 1, it, PendingIntent.FLAG_ONE_SHOT);

        // cancel turbo mode after 20 sec.
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        long now = SystemClock.elapsedRealtime();
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 20, pit);
    }

    public void clearList(){
        listAdapter.clear();
        listAdapter.updateListView();
    }

    /**************** Override methods **************/


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
        stopThreads();
        clearList();

        /****************** AP(Hotspot) Mode *********************/
        if(id == R.id.action_client || id == R.id.action_server){

            if(!APmode){
                listUpdater = new UIUpdateThread(apmanager, listAdapter, UPDATE_PERIOD);
                listUpdater.start();
            }

            //if(!APmode) {
                APmode = true;
                listtitle.setText(R.string.ap_client_list);

                if (apmanager != null && !apmanager.isWifiApEnabled()) {

                    startB.setEnabled(false);
                    notifyOICClients(HandoffImpl.STOP_CLIENT);

                    apmanager.setWifiApEnabled(null, true);
                    new APFixThread().start();


                }
            //}

            if (id == R.id.action_server) {
                //if(!isServer){
                    isServer = true;
                    mode.setText(R.string.mode_server);
                //}
                //startBroadcast();
                return true;
            }else if(id== R.id.action_client) {
                //if (isServer) {
                    isServer = false;
                    mode.setText(R.string.mode_client);
                //}
                //startScan();
                return true;
            }

        }else{/****************** External AP Mode *********************/
            if(APmode) {
                APmode = false;
                listtitle.setText(R.string.vr_list);
                listUpdater.cancel();

                if (apmanager != null && apmanager.isWifiApEnabled()) {

                    notifyOICClients(HandoffImpl.STOP_CLIENT);
                    startB.setEnabled(false);

                    apmanager.setWifiApEnabled(null, false);
                    wifimanager.setWifiEnabled(true);

                    BroadcastReceiver connectDetector = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String action = intent.getAction();
                            if(WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)){
                                WifiManager manager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
                                NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                                NetworkInfo.State state = networkInfo.getState();
                        /*
                                ConnectivityManager connManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                                while(true){
                                    NetworkInfo netinfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                                    if(netinfo!=null){
                                        if(netinfo.isConnected()) break;
                                    }else{
                                        Log.w("HandoffClient", "network info == null");
                                    }
                                }
*/
                                if(state == NetworkInfo.State.CONNECTED){
                                    unregisterReceiver(this);
                                    startB.setEnabled(true);
                                    notifyOICClients(HandoffImpl.INIT_OIC_STACK);
                                }
                            }
                        }
                    };
                    IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                    registerReceiver(connectDetector, filter);
                }
            }
            //listUpdater.cancel();

            if(id == R.id.action_server2){
                //if(!isServer){

                    isServer = true;
                    mode.setText(R.string.mode_server2);
                //}

                //startBroadcast();
                return true;
            }else if(id == R.id.action_client2){
                //if(isServer) {
                    isServer = false;
                    mode.setText(R.string.mode_client2);
                //}
                //startScan();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

}
