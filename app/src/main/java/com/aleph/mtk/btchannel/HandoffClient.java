package com.aleph.mtk.btchannel;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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
import java.net.SocketException;
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

public class HandoffClient extends Thread {

    Handler uihandler;

    //for negotiation
    CState state;
    boolean running;
    private BluetoothDevice btdevice;
    private BluetoothSocket socket;
    private BluetoothAdapter btadapter;
    private WifiApManager apmanager;
    private WifiManager wmanager;

    //for hand-off
    public String ssid;
    public static int localport = 5678;
    public static int remoteport = 6789;
    public ArrayList<String> targets;
    public boolean ready = false;

    /**** IO *****/
    PrintStream ps;
    BufferedReader br;
    InputStream is;
    OutputStream os;
    String buffer;


    public HandoffClient(Handler h, BluetoothDevice device, BluetoothAdapter adapter, WifiApManager _apmanager, WifiManager _wmanager, UUID uuid){

        running = false;
        btdevice = device;
        uihandler = h;
        btadapter = adapter;
        apmanager = _apmanager;
        wmanager = _wmanager;
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
        // Cancel discovery because it will slow down the connection
        btadapter.cancelDiscovery();

        // Try to lock the data servers to hand
        boolean success  = lockResourceServer();
        if(!success){
            printui("Cannot lock resource server");
            resetButton();
            return;
        }

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
                        //client send info to server first
                        ps.println("DUMMY_INFO");
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
                        handOffWifi();
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

    private boolean lockResourceServer(){

        return true;
    }


    /* **************************************************************
     * Perform the actual hand-off progress.
     * Send hand-off command to the chosen devices by proxy
     * Currently send to all devices connected to AP
     * **************************************************************/
    private void handOffWifi(){
        targets = new ArrayList<String>();
        ready = false;

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
                    printui("  ip=" + ip + ", mac=" + mac + ", device="+name);
                    targets.add(ip);
                }
                ready = true;
            }
        });


        while(!ready){}//spin lock
        //send hand-off command to handed clients
        for(String t : targets) sendToServer(t);

        //try to connect to hand-off server
        connectProxyWifi();

        return;
    }

    /* **************************************************************
     * Helper function of hand-off progress.
     * Send hand-off commands through UDP
     * Currently send the target SSID only to the devices to be handed
     * **************************************************************/
    public void sendToServer(String ip){
        byte[] msg = ssid.getBytes(); //get from hand-off server (proxy)

        //send hand-off command to client
        try {
            InetAddress addr = InetAddress.getByName(ip);
            DatagramSocket udpsocket = new DatagramSocket();
            DatagramPacket data = new DatagramPacket(msg, msg.length, addr, remoteport);
            printui("send to "+ip+":"+remoteport);
            udpsocket.send(data);
            printui("after send");
            udpsocket.close();
        } catch (UnknownHostException e) {
            Log.e("handOffWifi", "UDP socket fail: cannot find host", e);
        } catch (SocketException e2){
            Log.e("handOffWifi", "Init socket fail", e2);
        } catch (IOException e3){
            Log.e("handOffWifi", "IO error fail", e3);
        }
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
        }else{
            printui("ERROR: enable network fail");
        }
    }
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

    public void cancel(){
            printui("thread canceled.");
            running = false;
            //if(socket!=null) socket.close();
    }

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

}
