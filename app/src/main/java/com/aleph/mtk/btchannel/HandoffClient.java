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
    CONNECTING, INIT, HANDSHAKE, EXCHANGING, EXCHANGING2, WAITING, HANDOFF, WAIT_HANDBACK, CLOSE_BT_SOCK
}


/* **************************************************************
  * Helper class of hand-off progress.
  * Send hand-off commands asynchronously using Thread.
  * Retry until ACK received or timeout
  * Currently send the target SSID only to the devices to be handed
  * **************************************************************/
public class HandoffClient extends Thread implements MyLogger{


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
    //private WifiApManager apmanager;
    //private WifiManager wmanager;

    HandoffImpl handoff;

    private InfoCenter infocenter;
    private Context mContext;

    //for hand-off
    public String ssid;
    public static int remoteport = Util.WIFI_COMMAND_PORT;
    public ArrayList<String> targets;
    public boolean ready = false;
    public boolean abortFlag = false;

    /**** IO *****/
    PrintStream ps;
    BufferedReader br;
    InputStream is;
    OutputStream os;
    String buffer;


    public HandoffClient(Context context, Handler h, HandoffImpl impl, BluetoothDevice device, InfoCenter ic, UUID _uuid, boolean apmode){

        mContext = context;
        btdevice = device;
        uihandler = h;
        //btadapter = adapter;
        //apmanager = _apmanager;
        //wmanager = _wmanager;
        infocenter = ic;
        uuid = _uuid;
        APmode = apmode;
        handoff = impl;

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
        while(running && count < MainActivity.MAX_RETRY) {
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
                //printui("ERROR: fail to connect the BT socket, retry..." + count);
                Log.d(TAG, "ERROR: fail to connect the BT socket, retry..." + count);
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


        try {

            /***************************** Client Main *******************************************/
            while(running) {
                switch(state){
                    case INIT:
                        tryNewRFCOMMSocket();
                        if(socket==null){
                            printui("ERROR: create RFCOMM socket failed");
                            return;
                        }
                        tryConnectBTServer();

                        if(ps!=null) {
                            printui("connected to server.");
                            state = CState.CONNECTING;
                        }else{
                            printui("ABORT: connected to server fail.");
                            running = false;
                        }
                        break;

                    case CONNECTING: // proxy found, send pkt. to start handshake
                        ps.println("START_HANDSHAKE");
                        ps.flush();
                        infocenter.updateInfo(); //trigger update first before actually get the info.
                        infocenter.setSSID(handoff.getApSSID());
                        infocenter.setMac(handoff.getMac());

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
                        if(APmode)handoff.notifyOICClients(HandoffImpl.STOP_CLIENT);

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

                                handoff.notifyOICClients(HandoffImpl.INIT_OIC_STACK);
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
                            handoff.handOffWifi(ssid);
                            closeAPlist();
                        }else{
                            handoff.notifyOICClients(HandoffImpl.SOFTINIT_OIC_STACK_PROXY);
                            //handoff.notifyOICClientsDelay(HandoffImpl.SOFTINIT_OIC_STACK_PROXY, 5);
                        }
                        // cannot notify OIC reinit stack after hand-off here?

                        //running = false;
                        state = CState.WAIT_HANDBACK;
                        break;

                    case WAIT_HANDBACK:
                        if(br.ready()) {
                            buffer = br.readLine();
                            printui("in WAIT_HANDBACK, rcv " + buffer);
                            if(buffer.equalsIgnoreCase("HANDBACK")){
                                ps.println("ACK");
                                ps.flush();

                                //notify all OIC clients to stop
                                handoff.notifyOICClients(HandoffImpl.STOP_CLIENT);
                                if(APmode) {
                                    //enable hotspot & restart OIC clients safely
                                    handoff.enableHotspotSafe();
                                }else{
                                    handoff.notifyOICClients(HandoffImpl.SOFTINIT_OIC_STACK);
                                    //handoff.notifyOICClientsDelay(HandoffImpl.INIT_OIC_STACK, 5);
                                }

                                state = CState.CLOSE_BT_SOCK;
                            }
                        }
                        break;

                    case CLOSE_BT_SOCK:

                        if(ps!=null) ps.close();
                        if(socket!=null) socket.close();

                        //running = false;
                        Util.sleep(1); // delay
                        state = CState.INIT;
                        break;
                        
                }

                //ps.println("HIHI");
                //ps.flush();
            }
            /******************************** End Main *******************************************/
            resetButton();

            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(ps!=null) ps.close();
        }
        printui("Handoff Client: end of client");
        return;
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
