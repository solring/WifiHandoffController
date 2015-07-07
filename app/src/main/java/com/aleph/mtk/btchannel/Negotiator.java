package com.aleph.mtk.btchannel;

import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.util.Log;

//import com.aleph.mtk.proxyservice.ProxyService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;

/**
 * Created by MTK07942 on 3/13/2015.
 */

enum SState{
    INIT, HANDSHAKE, EXCHANGING, CHECKING, SEND_RESULT, WAIT_ACK, WAIT_HANDBACK
}

public class Negotiator extends Thread {

    private SState state;
    private boolean running;
    private BluetoothSocket socket;
    private String buffer;
    private InputStreamReader is;
    private PrintStream ps;
    private BufferedReader br;
    private boolean apmode;

    //public ProxyService proxy;
    //private ArrayList<String> restypes;
    private JSONArray restypes;
    private String remote_ssid;
    private String remote_mac;

    //basic info
    private WifiConfiguration apconfig;
    HandoffServer hserver;
    HandoffImpl handoff;

    public Negotiator(HandoffServer s, BluetoothSocket _socket, HandoffImpl impl, boolean mode){
        running = false;
        hserver = s;
        socket = _socket;
        //apconfig = config;
        handoff = impl;
        apmode = mode;

        state = SState.INIT;
    }

    public void run(){

        JSONObject local, remote;
        String result = "ACCEPT";
        long last, now;
        int retry = 0; //the retry number of each socket connection
        last = now = System.currentTimeMillis();

        running = true;
        //Accept successfully
        if(socket!=null) {

            try {
                is = new InputStreamReader( socket.getInputStream() );
                br = new BufferedReader(is);

                ps = new PrintStream( socket.getOutputStream() );

            } catch (IOException e){
                hserver.printui("NEGOTIATOR: ERROR: IO stream init fail.");
                System.out.println(e);
                return;
            }

            try {
                /***************************** Negotiator Main ************************************/
                while(running){

                    switch(state){
                        case INIT: //waiting for clients request, no timeout here
                            buffer = br.readLine();
                            hserver.printui("in INIT: rcv " + buffer);
                            if (buffer.equalsIgnoreCase("START_HANDSHAKE")) {
                                //check if proxy services are available
                                this.state = SState.HANDSHAKE;
                            }
                            break;

                        case HANDSHAKE:
                            ps.println("ACK_HANDSHAKE"); //send ACK
                            ps.flush();

                            // Update device info here first!
                            hserver.infocenter.updateInfo();

                            state = SState.CHECKING;
                            now = last = System.currentTimeMillis();


                        case CHECKING: //Exchange information and check policy
                            now = System.currentTimeMillis();
                            //wait for client info
                            if(br.ready()) {
                                buffer = br.readLine();
                                hserver.printui("in CHECKING rcv: " + buffer);
                                /*
                                if (buffer.equalsIgnoreCase("DUMMY_INFO")) {
                                */
                                try{
                                    String info = hserver.infocenter.getInfo();
                                    local = new JSONObject(info);
                                    remote = new JSONObject(buffer);

                                    //Whatever send the SSID first
                                    ps.println(handoff.getApSSID());   //send proxy local addr to client
                                    ps.flush();

                                    //Check policy & get resource type
                                    result = checkPolicy(local, remote);
                                    if(result.equals("ACCEPT")) handoff.notifyOICClients(HandoffImpl.STOP_CLIENT);

                                    state = SState.SEND_RESULT;
                                }catch(JSONException e){
                                    hserver.printui("Negotiator: not in JSON format, continue to wait...");
                                }
                            }
                            else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                                if(retry < MainActivity.MAX_RETRY) {
                                    retry += 1;
                                    state = SState.HANDSHAKE; //resend
                                }
                            }
                            break;

                        case SEND_RESULT:
                            ps.println(result);
                            ps.flush();
                            //running = false;
                            now = last = System.currentTimeMillis();
                            state = SState.WAIT_ACK;
                            break;

                        case WAIT_ACK:
                            now = System.currentTimeMillis();
                            //wait for client info
                            if(br.ready()) {
                                buffer = br.readLine();
                                hserver.printui("in WAIT_ACK: rcv " + buffer);
                                if (buffer.equalsIgnoreCase("ACK_RESULT")) {

                                    /******** Start Proxy Service here ********/
                                    if(result.equalsIgnoreCase("ACCEPT")) {
                                        hserver.printui("ACCPET, try to start Proxy...");
                                        hserver.startProxyServices(restypes);
                                    }

                                    //running = false; //NEGOTIATION OVER
                                    state = SState.WAIT_HANDBACK;
                                }
                            }
                            else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                                if(retry < MainActivity.MAX_RETRY) {
                                    retry += 1;
                                    state = SState.SEND_RESULT; //resend
                                }
                            }
                            break;

                        case WAIT_HANDBACK:
                            //DO NOTHING BUT WAIT

                    } //end of switch
                }//end of while
                /************************* End Negotiator Main ************************************/

                /************************* Try to Hand-back ************************************/

                //Notify proxy client to enable hotspot
                ps.println("HANDBACK");
                ps.flush();
                now = last = System.currentTimeMillis();
                while(true) {
                    now = System.currentTimeMillis();
                    if(br.ready()) {
                        buffer = br.readLine();
                        hserver.printui("after WAIT_HANDBACK: rcv " + buffer);
                        if (buffer.equalsIgnoreCase("ACK")) {
                            break;
                        }
                    } else if(now - last > MainActivity.TIMEOUT){ //TIMEOUT
                        if(retry < MainActivity.MAX_RETRY) {
                            retry += 1;
                            ps.println("HANDBACK");
                            ps.flush();
                        }else{
                            break;
                        }
                    }
                }

                //Close BT connection
                br.close();
                socket.close();

                //Notify all AP client to go back (except the proxy client)
                if(hserver.apmode) {
                    handoff.notifyAllClients(remote_ssid, remote_mac);
                }

                //Log.d("BTChannel", "Negotiator: update ap client list faster");
                //hserver.updateList();
                hserver.printui("Negotiator: end of negotiator, socket closed.");

            } catch (IOException e) {
                hserver.printui("Negotiator: readline error");
                e.printStackTrace();
            }finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
    }



    private String checkPolicy(JSONObject local, JSONObject remote){
        String res = "REJECT";

        try {
            int l = local.getInt(InfoCenter.BATLEVEL);
            int c = local.getInt(InfoCenter.WIFI_CLIENT_NUM);
            int m = local.getInt(InfoCenter.MAX_BATLEVEL);
            double p = 100.0f * (double)c/(double)m;

            //get resource type here
            String tmp = remote.getString(InfoCenter.RES_TYPE);
            hserver.printui("JSON array: " + tmp);
            restypes = new JSONArray(tmp);
            //restypes = (ArrayList<String>)remote.get(InfoCenter.RES_TYPE);

            //get remote info
            remote_ssid = remote.getString(InfoCenter.SSID);
            remote_mac = remote.getString(InfoCenter.MAC);

            int level = remote.getInt(InfoCenter.BATLEVEL);
            int client_num = remote.getInt(InfoCenter.WIFI_CLIENT_NUM);
            int max_level = remote.getInt(InfoCenter.MAX_BATLEVEL);
            double percent = 100.0f * (double)level/(double)max_level;

            hserver.printui("Negotiator: checkPolicy: remote bat level=" + percent + ", client_num=" + client_num);
            hserver.printui("Negotiator: checkPolicy: local bat level=" + p + ", client_num=" + c);

            /************** Main Policy Logic ***************/
            if(percent < 90 && client_num < 10) res = "ACCEPT";

            if(percent < p && client_num < 10 && c < 4) res = "ACCEPT";
            /************ End Main Policy Logic **************/


        }catch(JSONException e){
            hserver.printui("Negotiator: Cannot get infos.");
            e.printStackTrace();
        }

        //return res;
        return "ACCEPT";
    }


    public void cancel(){
        hserver.printui("Negotiator: canceled.");
        //proxy.cancel();
        running = false;
    }
}
