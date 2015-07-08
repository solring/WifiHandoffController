package com.aleph.mtk.btchannel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import com.whitebyte.wifihotspotutils.ClientScanResult;
import com.whitebyte.wifihotspotutils.FinishScanListener;
import com.whitebyte.wifihotspotutils.WifiApManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by MTK07942 on 7/3/2015.
 */
public class HandoffImpl {

    private final static String TAG = "HandoffImpl";

    public final static String INIT_OIC_STACK = "org.iotivity.base.examples.INIT_OIC_STACK";
    public final static String INIT_OIC_STACK_PROXY = "org.iotivity.base.examples.INIT_OIC_STACK_PROXY";
    public final static String STOP_CLIENT = "org.iotivity.base.examples.STOP_CLIENT";
    public final static String SOFTINIT_OIC_STACK_PROXY = "org.iotivity.base.examples.SOFTINIT_OIC_STACK_PROXY";
    public final static String SOFTINIT_OIC_STACK = "org.iotivity.base.examples.SOFTINIT_OIC_STACK";

    public final static int REINIT_DELAY = 3;

    private ArrayList<String> targets;
    private Context mContext;
    private WifiApManager apmanager;
    private WifiManager wmanager;
    private MyLogger logger;

    //private String ssid;
    private boolean ready, abortFlag;

    public class APFixThread extends Thread {
        public void run() {

            while (!apmanager.isWifiApEnabled()) {
            } //simple pin lock
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

                } catch (IOException e1) {

                } catch (InterruptedException e2) {
                    e2.printStackTrace();
                }
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    public class CommandThread extends Thread{

        private String target = "";
        private int port = 0;
        private String TAG = "CommandThread";
        private final static int TIMEOUT_MILL = 15000; //15 secs
        private final static int READ_TIMEOUT_MILI = 1000; // 1 sec
        private final static int MAX_RETRY = 10;
        private String ssid;


        public CommandThread(String ip, int _port, String tid){
            target = ip;
            port = _port;
            ssid = tid;
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
                    break;
                } catch (SocketException e2) {
                    count++;
                    Log.e(TAG, "Init TCP socket SocketException ", e2);
                    //break;
                } catch (IOException e3){
                    Log.e(TAG, "Init TCP socket IOException ", e3);  //usually block 90000ms?
                    break;
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
                        Log.d(TAG, "send command to " + target + ":" + Util.WIFI_COMMAND_PORT);

                        try {
                            String res = reader.readLine(); //blocking
                            Log.d(TAG, "received from " + target + ":" + Util.WIFI_COMMAND_PORT);
                            if (res!=null && res.equalsIgnoreCase("ACK")) {
                                printui("received ACK from " + target + ":" + Util.WIFI_COMMAND_PORT);
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
                DatagramPacket data = new DatagramPacket(msg, msg.length, addr, Util.WIFI_COMMAND_PORT);

                while(true) {
                    try{
                        printui("send to " + ip + ":" + Util.WIFI_COMMAND_PORT);
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

    public HandoffImpl(Context context, WifiApManager am, WifiManager wm, MyLogger l){
        apmanager = am;
        wmanager = wm;
        mContext = context;
        logger = l;
    }

    /* **************************************************************
    * Try to enable hotspot
    * After enabled, notify local OIC clients to re-start
    * **************************************************************/
    public void enableHotspotSafe(){
        if(apmanager!=null && !apmanager.isWifiApEnabled()) apmanager.setWifiApEnabled(null, true);

        while (!apmanager.isWifiApEnabled()) {} //simple pin lock
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

            } catch (IOException e1) {

            } catch (InterruptedException e2) {
                e2.printStackTrace();
            }
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        notifyOICClientsDelay(INIT_OIC_STACK, REINIT_DELAY);
    }



    /* **************************************************************
    * Perform the actual hand-off progress.
    * Send hand-off command to the chosen devices by proxy
    * Currently send to all devices connected to AP
    * **************************************************************/
    public void handOffWifi(String ssid){

        notifyAllClients(ssid);
        //try to connect to hand-off server
        connectProxyWifi(ssid);

    }

    public void notifyAllClients(String ssid){
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

        //send hand-off command to handed clients
        ArrayList<CommandThread> ths = new ArrayList();
        for(String ip : targets) {
            CommandThread th = new CommandThread(ip, Util.WIFI_COMMAND_PORT, ssid);
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
    }

    public void notifyAllClients(String ssid, final String except_mac){
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
                            //skip the exceptional mac
                            if(except_mac!=null && except_mac.equalsIgnoreCase(m))
                            {
                                printui("skip mac " + m);
                                continue;
                            }

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

        //send hand-off command to handed clients
        ArrayList<CommandThread> ths = new ArrayList();
        for(String ip : targets) {
            CommandThread th = new CommandThread(ip, Util.WIFI_COMMAND_PORT, ssid);
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
    }

    /* **************************************************************
     * Helper function of hand-off progress.
     * Close Wi-fi AP, enable Wi-fi, and connect to the hand-off server (proxy)
     * **************************************************************/
    public void connectProxyWifi(final String ssid){
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
                            connectAP(1, ssid);
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
            connectAP(1, ssid); //connect to the 1st prio AP in the AP list
        }
        //while(wmanager.getWifiState()!=WifiManager.WIFI_STATE_ENABLED){} //spin lock


    }

    /* **************************************************************
     * Helper function of hand-off progress.
     * Connect to the specific AP after enabling Wi-Fi
     * **************************************************************/
    public void connectAP(int origin, String ssid){

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
            notifyOICClientsDelay(INIT_OIC_STACK, REINIT_DELAY);

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


    private void printui(String s){
        logger.printui(s);
    }

    public void notifyOICClients(String action){
        printui("Handoff client: Notify all Iotivity clients ..." + action);
        Intent notify = new Intent(action);
        mContext.sendBroadcast(notify);
    }

    public void notifyOICClientsDelay(String action, int delaySec){
        printui("notify OIC Clients, action: "+action);

        Intent notify = new Intent(action);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 1, notify, PendingIntent.FLAG_ONE_SHOT);

        //long trigger = SystemClock.elapsedRealtime() + delaySec * 1000;
        long trigger = System.currentTimeMillis() + delaySec * 1000;
        AlarmManager am  = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        //am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
    }


    public String getApSSID(){
        if(apmanager!=null) return apmanager.getWifiApConfiguration().SSID;
        else return "";
    }

    public String getMac(){
        if(wmanager!=null){
            WifiInfo info = wmanager.getConnectionInfo();
            if(info!=null) return info.getMacAddress();
        }
        return "";
    }
}
