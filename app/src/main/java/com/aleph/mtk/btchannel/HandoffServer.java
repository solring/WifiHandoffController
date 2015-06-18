package com.aleph.mtk.btchannel;

/**
 * Created by MTK07942 on 3/11/2015.
 */


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.net.wifi.WifiConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.whitebyte.wifihotspotutils.WifiApManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;


public class HandoffServer extends Thread {

    private boolean running;
    private BluetoothServerSocket btserver;
    private BluetoothAdapter btadapter;
    private ArrayList<Negotiator> proxies;
    private WifiConfiguration apconfig;

    public InfoCenter infocenter;
    public Handler uihandler;

    public HandoffServer(Handler h, BluetoothAdapter adapter, WifiConfiguration config, InfoCenter ic, UUID uuid){
        uihandler = h;
        btadapter = adapter;
        running = false;
        btserver = null;
        apconfig = config;

        infocenter = ic;

        proxies = new ArrayList<Negotiator>();

        try{
            btserver = btadapter.listenUsingRfcommWithServiceRecord("Hand-off Proxy", uuid);
        }catch(IOException e){
            printui("Handoff server ERROR: fail to listen bt connect requests...");
            System.out.println(e.toString());
        }
    }

    /****** Handoff server accepting requests *****/
    public void run(){
        running = true;
        String buffer;

        System.out.println("in HandoffServer run(): Hand-off Service Started...");
        printui("Hand-off Service Started...");

        if(btserver==null){
            printui("Handoff server btserver == null");
            return;
        }

        BluetoothSocket socket;

        //Listen client connections.....
        try{
            while(running){

                socket = btserver.accept();                //Can only be close() by another thread
                //if(socket!=null) btserver.close();
                if(socket==null) continue;

                //New negotiator for each request
                Negotiator session = new Negotiator(this, socket, apconfig);

                proxies.add(session);
                session.start();
            }
            btserver.close();
            printui("Handoff server: Server thread canceled, close socket");

        }catch(IOException e){
            printui("Handoff server: accept fail");
            e.printStackTrace();

            Message msg = new Message();
            Bundle data = new Bundle();
            data.putString("status", "stopped");
            msg.setData(data);
            uihandler.sendMessage(msg);
        }

        printui("Handoff server: end of run()");
    }

    public void cancel(){
        printui("Handoff server: canceled.");
        running = false;
        try {
            btserver.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //Stop all negotiators
        for(Negotiator n : proxies){
            n.cancel();
        }
    }

    public synchronized void printui(String str){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString(MainActivity.MSG_PRINT, str );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

    public synchronized void updateList(){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString(MainActivity.MSG_COMMAND, MainActivity.CMD_UPDATELIST );
        msg.setData(data);
        uihandler.sendMessage(msg);

    }

}
