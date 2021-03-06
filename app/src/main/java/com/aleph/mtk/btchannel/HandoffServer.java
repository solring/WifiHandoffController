package com.aleph.mtk.btchannel;

/**
 * Created by MTK07942 on 3/11/2015.
 */


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.whitebyte.wifihotspotutils.WifiApManager;

import org.iotivity.base.OcResource;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;


public class HandoffServer extends Thread implements MyLogger{

    /***********WORKAROUND***************
     * To prevent crash due to releasing or unregister OcResource
     ************************************/
    private static HashMap<String, HashMap<String, ProxyResource>> trashBin = new HashMap<String, HashMap<String, ProxyResource>>();

    private boolean running;

    private MainActivity mContext;
    private BluetoothServerSocket btserver;
    private BluetoothAdapter btadapter;
    private ArrayList<Negotiator> negotiators;

    private HashMap<String, ProxyService> proxyservices;
    //private WifiConfiguration apconfig;
    private HandoffImpl handoff;

    public boolean apmode;
    public String ssid;
    public InfoCenter infocenter;
    public Handler uihandler;

    public HandoffServer(MainActivity context, Handler h, HandoffImpl impl, BluetoothAdapter adapter, InfoCenter ic, UUID uuid, Boolean mode){
        mContext = context;
        uihandler = h;
        btadapter = adapter;
        running = false;
        btserver = null;
        //apconfig = config;
        infocenter = ic;
        apmode = mode;
        handoff = impl;

        negotiators = new ArrayList<Negotiator>();
        proxyservices = new HashMap<String, ProxyService>();

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

        printui("Hand-off Service Started... apmode="+apmode);

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
                Negotiator session = new Negotiator(this, socket, handoff, apmode);

                negotiators.add(session);
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
        } finally {
            try {
                btserver.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        printui("Handoff server: end of run()");
    }


    synchronized public void startProxyServices(JSONArray restypes){
        //enable proxy service only on non-apmode now
        if(!apmode) {
            // make a proxy service thread for every resource types
            if (restypes != null && restypes.length() > 0) {
                for (int i = 0; i < restypes.length(); i++) {
                    String res = null;
                    try {
                        res = restypes.getString(i);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    printui("Accepted, start the proxy service with resource: " + res + "......");
                    //new and start proxy thread...
                    if (!proxyservices.containsKey(res)) {
                        ProxyService proxy = new ProxyService(res, "0.0.0.0", 0, uihandler, mContext.listAdapter);
                        proxy.start();
                        proxyservices.put(res, proxy);
                    }
                }
                //notifyOICClients(HandoffClient.INIT_OIC_STACK_PROXY);
                handoff.notifyOICClientsDelay(HandoffImpl.SOFTINIT_OIC_STACK_PROXY, 5);
            }
        }else{
            handoff.notifyOICClientsDelay(HandoffImpl.INIT_OIC_STACK, 5);
        }
    }

    public void cancel(){
        printui("Handoff server: canceled.");
        running = false;

        //Stop local clients
        handoff.notifyOICClients(HandoffImpl.STOP_CLIENT);

        //Stop all negotiators & make them notify proxy clients to enable hotspot
        for(Negotiator n : negotiators){
            n.cancel();
        }

        //Stop all proxy
        for(ProxyService p: proxyservices.values()){
            p.stopProxy();
        }

        //Stop BT server
        try {
            btserver.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /******* WORKAROUND HELPER FUNCTION *******/
    public static void putToBin(String restype, HashMap map){
        if(!trashBin.containsKey(restype))
            trashBin.put(restype, map);
    }

    public static HashMap getFromBin(String restype){
        if(trashBin.containsKey(restype)) return trashBin.get(restype);
        else return null;
    }

    public static void clearBin(){
        trashBin.clear();
    }
    /******************************************/

    public synchronized void printui(String str){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString(MainActivity.MSG_PRINT, str );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

    /*
    public synchronized void updateList(){
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString(MainActivity.MSG_COMMAND, MainActivity.CMD_UPDATELIST );
        msg.setData(data);
        uihandler.sendMessage(msg);
    }*/

}
