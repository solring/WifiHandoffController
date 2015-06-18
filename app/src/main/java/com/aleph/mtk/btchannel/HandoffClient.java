package com.aleph.mtk.btchannel;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
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
        private final static int TIMEOUT_MILL = 10000; //10 secs



        public CommandThread(String ip, int _port){
            target = ip;
            port = _port;
        }

        public void run(){
            sendToServerUDP(target);
            //sendToServerTCP
        }

        public void sendToServerTCP(){
            BufferedReader reader;
            PrintStream printf;
            boolean success = false;
            Log.d(TAG, "command channel of " +  target + "start");

            try {
                Socket socket = new Socket(target, port);
                Log.d(TAG, "connect to target " +  target);

                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                printf = new PrintStream(socket.getOutputStream());

                while(!success) {

                    printf.println(ssid);
                    printf.flush();
                    Log.d(TAG, "send command to " + target + ":" + remoteport);

                    String res = reader.readLine(); //blocking
                    Log.d(TAG, "received from " + target + ":" + remoteport);
                    if (res.equalsIgnoreCase("ACK")) {
                        Log.d(TAG, "received ACK from " + target + ":" + remoteport);
                        success = true;
                    }

                }
                printf.close();
                reader.close();
                socket.close();

                //Log.d(TAG, "send to " + target + ":" + remoteport);
            } catch (UnknownHostException e) {
                Log.e(TAG, "TCP socket fail: cannot find host" + target, e);
            } catch (SocketException e2){
                Log.e(TAG, "Init TCP socket fail: " + target, e2);
            } catch (IOException e3){
                Log.e("handOffWifi", "IO error fail", e3);
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
                udpsocket.setSoTimeout(1000);  //retry every sec.

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

    public final static String TAG = "HandoffClient";

    Handler uihandler;

    //for negotiation
    CState state;
    boolean running;
    private BluetoothDevice btdevice;
    private BluetoothSocket socket;
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


    public HandoffClient(Context context, Handler h, BluetoothDevice device, WifiApManager _apmanager, WifiManager _wmanager, InfoCenter ic, UUID uuid){

        mContext = context;
        running = false;
        btdevice = device;
        uihandler = h;
        //btadapter = adapter;
        apmanager = _apmanager;
        wmanager = _wmanager;
        infocenter = ic;
        BluetoothSocket tmp = null;
        state = CState.INIT;

        try{
            tmp = btdevice.createRfcommSocketToServiceRecord(uuid);
        }catch(IOException e){
            printui("Handoff client ERROR: fail to connect the device...");
            System.out.println(e.toString());
        }
        socket = tmp;
    }

    public void run() {

        long last, now;
        int retry = 0; //the retry number of each socket connection
        last = now = System.currentTimeMillis();
        running = true;

        try {
            // Connect the device through the socket. This will block
            // until it succeeds or throws an exception
            socket.connect();

            os = socket.getOutputStream();
            is = socket.getInputStream();
            ps = new PrintStream(os);
            br = new BufferedReader(new InputStreamReader(is));

        } catch (IOException connectException) {
            // Unable to connect; close the socket and get out
            printui("ERROR: fail to connect the socket");
            try {
                socket.close();
            } catch (IOException closeException) { }
            return;
        }

        // Do work to manage the connection (in a separate thread)
        printui("connect to server.");
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
                        handOffWifi();
                        closeAPlist();
                        //re-init OIC clients
                        //notifyOICClients(INIT_OIC_STACK_PROXY);
                        notifyOICClients(INIT_OIC_STACK);

                        running = false;
                        break;
                }

                //ps.println("HIHI");
                //ps.flush();
            }
            /******************************** End Main *******************************************/
            resetButton();
            printui("close socket.");
            ps.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
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

        apmanager.getClientList(false, new FinishScanListener() {
            @Override
            public void onFinishScan(final ArrayList<ClientScanResult> clients) {
                String ip, mac, name;

                for (ClientScanResult c : clients) {
                    ip = c.getIpAddr();
                    mac = c.getHWAddr();
                    name = c.getDevice();

                    printui("--- in getClientList ---");
                    printui("  ip=" + ip + ", mac=" + mac + ", device=" + name);
                    targets.add(ip);
                }
                ready = true;
            }
        });
        while(!ready){}//spin lock


        Log.d("HandoffClient", "starting command threads...");
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

        if(abortFlag) {
            printui("Not all AP clients handed over successfully");
        }
        printui("All AP clients notified.");
        //try to connect to hand-off server
        connectProxyWifi();

    }



    /* **************************************************************
     * Helper function of hand-off progress.
     * Close Wi-fi AP and connect to the hand-off server (proxy)
     * **************************************************************/
    public void connectProxyWifi(){
        WifiInfo info = wmanager.getConnectionInfo();

        int origin = info.getNetworkId();
        int target = origin;

        //disable wifi ap
        apmanager.setWifiApEnabled(null, false);

        if(!wmanager.isWifiEnabled()) wmanager.setWifiEnabled(true);
        while(wmanager.getWifiState()!=WifiManager.WIFI_STATE_ENABLED){} //spin lock

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
        printui("Notify all Iotivity clients ..." + action);
        Intent notify = new Intent(action);
        mContext.sendBroadcast(notify);
    }


    public void cancel(){
            printui("thread canceled.");
            running = false;
            //if(socket!=null) socket.close();
    }

    /************************** UI functions ***************************/
    public void printui(String str){
        System.out.println("Handoff client:" + str);
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("data", "Handoff client:" + str );
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
