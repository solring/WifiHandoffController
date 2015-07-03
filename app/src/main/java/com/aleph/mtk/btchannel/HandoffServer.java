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

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;


public class HandoffServer extends Thread {

    private boolean running;
    private boolean apmode;

    private Context mContext;
    private BluetoothServerSocket btserver;
    private BluetoothAdapter btadapter;
    private ArrayList<Negotiator> negotiators;
    private HashMap<String, ProxyService> proxyservices;
    private WifiConfiguration apconfig;

    public InfoCenter infocenter;
    public Handler uihandler;

    public HandoffServer(Context context, Handler h, BluetoothAdapter adapter, WifiConfiguration config, InfoCenter ic, UUID uuid, Boolean mode){
        mContext = context;
        uihandler = h;
        btadapter = adapter;
        running = false;
        btserver = null;
        apconfig = config;
        infocenter = ic;
        apmode = mode;

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
                Negotiator session = new Negotiator(this, socket, apconfig);

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


    public void startProxyServices(JSONArray restypes){
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
                        ProxyService proxy = new ProxyService(res, "0.0.0.0", 0, uihandler);
                        proxy.start();
                        proxyservices.put(res, proxy);
                    }
                }
                //notifyOICClients(HandoffClient.INIT_OIC_STACK_PROXY);
                notifyOICClientsDelay(HandoffClient.SOFTINIT_OIC_STACK_PROXY, 5);
            }
        }else{
            notifyOICClientsDelay(HandoffClient.INIT_OIC_STACK, 5);
        }
    }

    public void cancel(){
        printui("Handoff server: canceled.");
        running = false;

        //Stop all negotiators
        for(Negotiator n : negotiators){
            n.cancel();
        }
        for(ProxyService p: proxyservices.values()){
            p.stopProxy();
        }
        try {
            btserver.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void notifyOICClients(String action){
        printui("Handoff server: Notify all Iotivity clients ..." + action);
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
