package com.aleph.mtk.btchannel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.whitebyte.wifihotspotutils.ClientScanResult;
import com.whitebyte.wifihotspotutils.FinishScanListener;
import com.whitebyte.wifihotspotutils.WifiApManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Created by MTK07942 on 3/12/2015.
 */

enum CState{
    INIT, HANDSHAKE, EXCHANGING, EXCHANGING2, WAITING, HANDOFF
}


/* **************************************************************
  * Helper class of hand-off progress.
  * Send hand-off commands asynchronously using Thread.
  * Retry until ACK received or timeout
  * Currently send the target SSID only to the devices to be handed
  * **************************************************************/
public class HandoffClient extends Thread {

    public class CommandThread extends Thread{

        private String target = "";
        private int port = 0;
        private String TAG = "CommandThread";
        private final static int TIMEOUT_MILL = 15000; //15 secs
        private final static int READ_TIMEOUT_MILI = 1000; // 1 sec
        private final static int MAX_RETRY = 10;


        public CommandThread(String ip, int _port){
            target = ip;
            port = _port;
        }

        public void run(){
            //sendToServerUDP(target);
            sendToServerTCP();
        }

        public void sendToServerTCP(){
            BufferedReader reader;
            PrintStream printf;
            boolean success = false;
            Log.d(TAG, "command channel of " +  target + "start");
            Socket socket = null;

            /********** Try to connect AP clients TCP socket **********/
            int count = 0;
            while(count < MAX_RETRY) {
                try {
                    socket = new Socket(target, port);
                    Log.d(TAG, "connect to target " +  target);
                    socket.setSoTimeout(READ_TIMEOUT_MILI);
                    break;
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Init TCP socket fail: cannot find host" + target, e);
                } catch (SocketException e2) {
                    count++;
                    Log.e(TAG, "Init TCP socket SocketException ", e2);
                } catch (IOException e3){
                    Log.e(TAG, "Init TCP socket IOException ", e3);
                }

                Log.e(TAG, "Init TCP socket fail - retry # " + count);
                count++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if(count > MAX_RETRY){
                printui("ERROR: Fail to Init TCP socket to target "+ target);
                return;
            }

            count = 0;
            if(socket!=null) {
                try {
                    reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    printf = new PrintStream(socket.getOutputStream());

                    while (!success && count < MAX_RETRY) {

                        printf.println(ssid);
                        printf.flush();
                        Log.d(TAG, "send command to " + target + ":" + remoteport);

                        try {
                            String res = reader.readLine(); //blocking
                            Log.d(TAG, "received from " + target + ":" + remoteport);
                            if (res!=null && res.equalsIgnoreCase("ACK")) {
                                printui("received ACK from " + target + ":" + remoteport);
                                success = true;
                            }
                        }catch (SocketTimeoutException e4){
                            count++;
                            printui("rcv ACK timeout from " + target + " #" + count);
                        }
                    }
                    printf.close();
                    reader.close();
                    socket.close();
                    //Log.d(TAG, "send to " + target + ":" + remoteport);

                } catch (IOException e3){
                    Log.e(TAG, "IO error fail", e3);
                }
            }
        }

        public void sendToServerUDP(String ip){

            byte[] buffer = new byte[255];

            double startTime = System.currentTimeMillis();
            double now = startTime;
            //send hand-off command to client
            try {
                InetAddress addr = InetAddress.getByName(ip);
                DatagramSocket udpsocket = new DatagramSocket();
                udpsocket.setSoTimeout(500);  //retry every 0.5 sec.

                //String tmp = ssid + "/" + udpsocket.getPort();
                byte[] msg = ssid.getBytes(); //get from hand-off server (proxy)
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                DatagramPacket data = new DatagramPacket(msg, msg.length, addr, remoteport);

                while(true) {
                    try{
                        printui("send to " + ip + ":" + remoteport);
                        udpsocket.send(data);

                        udpsocket.receive(p);
                        String res = new String(buffer, 0, p.getLength());
                        if(res.contains("ACK")) break;

                        //wait for ack
                    }catch(SocketTimeoutException e){

                        //break if timeout
                        now = System.currentTimeMillis();
                        if(now - startTime > TIMEOUT_MILL) {
                            Log.w(TAG, "send to " + ip + " time up, abort this thread...");
                            abortFlag = true;
                            break;
                        }

                        Log.w(TAG, "Receive UDP packet timeout, try again...");

                    }catch (IOException e3) {
                        Log.e("handOffWifi", "IO error fail, break", e3);
                        break;
                    }
                }
                udpsocket.close();

            } catch (UnknownHostException e) {
                Log.e("handOffWifi", "UDP socket fail: cannot find host", e);
            } catch (SocketException e2) {
                Log.e("handOffWifi", "Init socket fail", e2);
            }
        }
    }

    public final static String INIT_OIC_STACK = "org.iotivity.base.examples.INIT_OIC_STACK";
    public final static String INIT_OIC_STACK_PROXY = "org.iotivity.base.examples.INIT_OIC_STACK_PROXY";
    public final static String STOP_CLIENT = "org.iotivity.base.examples.STOP_CLIENT";
    public final static String SOFTINIT_OIC_STACK_PROXY = "org.iotivity.base.examples.SOFTINIT_OIC_STACK_PROXY";
    public final static int BT_RETRY_TIMEOUT = 3;
    public final static String TAG = "HandoffClient";

    Handler uihandler;

    //for negotiation
    CState state;
    boolean running;
    private boolean APmode;

    private BluetoothDevice btdevice;
    private BluetoothSocket socket;
    private UUID uuid;
    //private BluetoothAdapter btadapter;
    private WifiApManager apmanager;
    private WifiManager wmanager;
    private InfoCenter infocenter;
    private Context mContext;

    //for hand-off
    public String ssid;
    public static int localport = 5678;
    public static int remoteport = 6789;
    public ArrayList<String> targets;
    public boolean ready = false;
    public boolean abortFlag = false;

    /**** IO *****/
    PrintStream ps;
    BufferedReader br;
    InputStream is;
    OutputStream os;
    String buffer;


    public HandoffClient(Context context, Handler h, BluetoothDevice device, WifiApManager _apmanager, WifiManager _wmanager, InfoCenter ic, UUID _uuid, boolean apmode){

        mContext = context;
        btdevice = device;
        uihandler = h;
        //btadapter = adapter;
        apmanager = _apmanager;
        wmanager = _wmanager;
        infocenter = ic;
        uuid = _uuid;
        APmode = apmode;

        running = false;
        state = CState.INIT;

        ps = null;
        br= null;
        is = null;
        os = null;

    }

    private void tryNewRFCOMMSocket(){

        int count = 0;
        BluetoothSocket tmp = null;
        while(count < MainActivity.MAX_RETRY) {
            try {
                tmp = btdevice.createRfcommSocketToServiceRecord(uuid);
                break;
            } catch (IOException e) {
                count++;
                printui("ERROR: fail to connect RFCOMM... retry " + count);
                Util.sleep(BT_RETRY_TIMEOUT);
            }
        }
        socket = tmp;
    }

    private void tryConnectBTServer(){
        int count = 0;
        while(running && count < 10000) {
            /********** Try to connect the BT servers socket **********/
            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception
                socket.connect();

                os = socket.getOutputStream();
                is = socket.getInputStream();
                ps = new PrintStream(os);
                br = new BufferedReader(new InputStreamReader(is));
                return;
            } catch (IOException connectException) {
                // Unable to connect; close the socket and get out
                count++;
                printui("ERROR: fail to connect the BT socket, retry..." + count);
                Util.sleep(BT_RETRY_TIMEOUT);
            }
        }
        //exceed retry times
        try {
            socket.close();
        } catch (IOException closeException) {}
        printui("ERROR: fail to connect the BT socket " + count + " times. abort.");
    }

    public void run() {

        long last, now;
        int retry = 0; //the retry number of each socket connection
        last = now = System.currentTimeMillis();
        running = true;

        tryNewRFCOMMSocket();
        if(socket==null){
            printui("ERROR: create RFCOMM socket failed");
            return;
        }

        tryConnectBTServer();

        // Do work to manage the connection (in a separate thread)
        printui("connected to server.");
        try {

            /***************************** Client Main *******************************************/
            while(running) {
                switch(state){
                    case INIT: // proxy found, send pkt. to start handshake
                        ps.println("START_HANDSHAKE");
                        ps.flush();
                        infocenter.updateInfo(); //trigger update first before actually get the info.

                        state = CState.HANDSHAKE;
                        now = last = System.currentTimeMillis();
                        break;

                    case HANDSHAKE:
                        now = System.currentTimeMillis();
                        if(br.ready()) {
                            buffer = br.readLine();
                            printui("in HANDSHAKE, rcv " + buffer);

                            if (buffer.equalsIgnoreCase("ACK_HANDSHAKE")) {
                                //ps.println("END_HANDSHAKE"); ps.flush();
                                state = CState.EXCHANGING;
                            }
                        }
                        else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                            if(retry < MainActivity.MAX_RETRY) {
                                retry += 1;
                                state = CState.INIT; //resend
                            }
                        }
                        break;

                    case EXCHANGING:
                        //stop local clients first
                        notifyOICClients(STOP_CLIENT);

                        //client send info to server first
                        String info = infocenter.getInfo();
                        ps.println(info);
                        ps.flush();

                        state = CState.WAITING;
                        now = last = System.currentTimeMillis();
                        break;


                    case WAITING:
                        now = System.currentTimeMillis();
                        if(br.ready()) {
                            buffer = br.readLine();
                            printui("in WAITING, rcv " + buffer);

                            if (buffer.equalsIgnoreCase("ACCEPT")) {
                                printui("--------- ACCEPTED!!!!!");
                                ps.println("ACK_RESULT");ps.flush();

                                state = CState.HANDOFF;

                            } else if (buffer.equalsIgnoreCase("REJECT")) {
                                printui("--------- REJECTED!!!!! QQ");
                                ps.println("ACK_RESULT");ps.flush();

                                notifyOICClients(INIT_OIC_STACK);
                                running = false;

                            } else {
                                //Get proxy wifi addr
                                printui("Get target SSPD:" + buffer);
                                state = CState.WAITING;
                                ssid = buffer;
                            }
                        }
                        else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                            if(retry < MainActivity.MAX_RETRY) {
                                retry += 1;
                                state = CState.EXCHANGING; //resend
                            }
                        }
                        break;

                    case HANDOFF:
                        //Start actual hand-off process
                        //This will block until Wi-Fi is associated
                        if(APmode) {
                            handOffWifi();
                            closeAPlist();
                        }else{
                            //waitOIC();       /* Work around for Iotivity */
                            //notifyOICClients(SOFTINIT_OIC_STACK_PROXY);
                            notifyOICClientsDelay(SOFTINIT_OIC_STACK_PROXY, 5);
                        }
                        // cannot notify OIC reinit stack after hand-off here?


                        running = false;
                        break;
                }

                //ps.println("HIHI");
                //ps.flush();
            }
            /******************************** End Main *******************************************/
            resetButton();

            if(ps!=null) ps.close();
            if(socket!=null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(ps!=null) ps.close();
        }
        printui("Handoff Client: end of client");
        return;
    }


    /* **************************************************************
     * Perform the actual hand-off progress.
     * Send hand-off command to the chosen devices by proxy
     * Currently send to all devices connected to AP
     * **************************************************************/
    private void handOffWifi(){

        targets = new ArrayList<String>();
        ready = false;
        abortFlag = false;

        //TEMP: list all connected hot-spot clients
        //targets here should be provided by the proxy

        final ArrayList<String> maclist = apmanager.getClientListMTK();

        apmanager.getClientList(false, new FinishScanListener() {
            @Override
            public void onFinishScan(final ArrayList<ClientScanResult> clients) {
                String ip, mac, name;

                for (ClientScanResult c : clients) {
                    ip = c.getIpAddr();
                    mac = c.getHWAddr();
                    name = c.getDevice();

                    //check if the mac is really connected
                    for(String m : maclist){
                        if(m.equalsIgnoreCase(mac)){
                            printui("  ip=" + ip + ", mac=" + mac + ", device=" + name);
                            targets.add(ip);
                            break;
                        }
                    }
                }
                ready = true;
            }
        });
        while(!ready){}//spin lock


        Log.d(TAG, "starting command threads...");
        //send hand-off command to handed clients
        ArrayList<CommandThread> ths = new ArrayList();
        for(String ip : targets) {
            CommandThread th = new CommandThread(ip, remoteport);
            th.start();
            ths.add(th);
            //sendToServer(ip);
        }
        for(CommandThread t : ths) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (abortFlag) {
            printui("Not all AP clients handed over successfully");
        }
        printui("All AP clients notified.");
        //try to connect to hand-off server
        connectProxyWifi();

    }



    /* **************************************************************
     * Helper function of hand-off progress.
     * Close Wi-fi AP, enable Wi-fi, and connect to the hand-off server (proxy)
     * **************************************************************/
    public void connectProxyWifi(){
        //WifiInfo info = wmanager.getConnectionInfo();
        //int origin = info.getNetworkId();
        //int target = origin;

        //disable wifi ap
        apmanager.setWifiApEnabled(null, false);

        if(!wmanager.isWifiEnabled()) {
            wmanager.setWifiEnabled(true);

            BroadcastReceiver rcv = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {

                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    switch (state) {
                        case WifiManager.WIFI_STATE_ENABLED:
                            Log.d(TAG, "onReceive: Wifi enabled");
                            connectAP(1);
                            mContext.unregisterReceiver(this);
                            break;
                        case WifiManager.WIFI_STATE_DISABLED:;
                        default:
                            //do nothing
                    }
                }
            };
            final IntentFilter filters = new IntentFilter();
            filters.addAction("android.net.wifi.WIFI_STATE_CHANGED");
            filters.addAction("android.net.wifi.STATE_CHANGE");
            mContext.registerReceiver(rcv, filters);

        }else{
            connectAP(1); //connect to the 1st prio AP in the AP list
        }
        //while(wmanager.getWifiState()!=WifiManager.WIFI_STATE_ENABLED){} //spin lock


    }

    /* **************************************************************
     * Helper function of hand-off progress.
     * Connect to the specific AP after enabling Wi-Fi
     * **************************************************************/
    public void connectAP(int origin){

        int target = origin;

        //Get the wifi configuration of hand-off server
        WifiConfiguration config = isExsits("\"" + ssid + "\"");
        if(config==null){
            config = new WifiConfiguration();
            config.allowedAuthAlgorithms.clear();
            config.allowedGroupCiphers.clear();
            config.allowedKeyManagement.clear();
            config.allowedPairwiseCiphers.clear();
            config.SSID = "\"" + ssid + "\"";

            //assume that the target proxy has no authentication
            //config.wepKeys[0] = "";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            //config.wepTxKeyIndex = 0;

            target = wmanager.addNetwork(config);
            if(target!=-1) {
                printui("New ssid = " + config.SSID + ", netid = " + target);
            }else{
                printui("ERROR: add network fail");
            }

        }else {
            printui("config already exist");
            target = config.networkId;
        }

        //Try to connect to the hand-off server
        if(wmanager.enableNetwork(target, true)){
            printui("enable network "+ target + " success");

            //simple spin lock
            //while(wmanager.getConnectionInfo().getSupplicantState()!= SupplicantState.COMPLETED){}
            ConnectivityManager connManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            while(true){
                NetworkInfo netinfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if(netinfo!=null){
                    if(netinfo.isConnected()) break;
                }else{
                    Log.w("HandoffClient", "network info == null");
                }
            }
            printui("New wifi ap connected.");

            //re-init OIC clients
            //Enable proxy service only in non-AP mode

            //waitOIC();
            //notifyOICClients(INIT_OIC_STACK);
            notifyOICClientsDelay(INIT_OIC_STACK, 10);

            //notifyOICClients(INIT_OIC_STACK_PROXY);


        }else{
            printui("ERROR: enable network fail");
        }
    }

    /* **************************************************************
     * Helper function of hand-off progress.
     * Check if the Wi-fi configureation already exists
     * **************************************************************/
    private WifiConfiguration isExsits(String SSID)
    {
        List<WifiConfiguration> existingConfigs = wmanager.getConfiguredNetworks();
        if(existingConfigs!=null) {
            for (WifiConfiguration config : existingConfigs) {
                if (config.SSID.equals(SSID)) return config;
            }
        }else{
            printui("ERROR: cannot get wifi configure");
        }
        return null;
    }

    private void notifyOICClients(String action){
        printui("Handoff client: Notify all Iotivity clients ..." + action);
        Intent notify = new Intent(action);
        mContext.sendBroadcast(notify);
    }

    private void notifyOICClientsDelay(String action, int delaySec){
        printui("notify OIC Clients, action: "+action);
        long trigger = SystemClock.elapsedRealtime() + delaySec * 1000;

        Intent notify = new Intent(action);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 1, notify, PendingIntent.FLAG_ONE_SHOT);

        AlarmManager am  = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
    }

    public void cancel(){
            printui("thread canceled.");
            running = false;
            //if(socket!=null) socket.close();
    }

    /************************** UI functions ***************************/
    public void printui(String str){
        //System.out.println("Handoff client:" + str);
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("data",str );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

    public void resetButton(){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("status", "stopped" );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

    public void closeAPlist(){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("status", "clear-list" );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

}
