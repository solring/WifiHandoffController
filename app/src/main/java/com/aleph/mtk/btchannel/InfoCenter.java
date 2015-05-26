package com.aleph.mtk.btchannel;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import com.whitebyte.wifihotspotutils.ClientScanResult;
import com.whitebyte.wifihotspotutils.WIFI_AP_STATE;
import com.whitebyte.wifihotspotutils.WifiApManager;
import com.whitebyte.wifihotspotutils.FinishScanListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Created by MTK07942 on 4/21/2015.
 */


public class InfoCenter extends Thread{

    public static final String BATLEVEL = "batLevel";
    public static final String MAX_BATLEVEL = "maxLevel";
    public static final String WIFI_CLIENT_NUM = "wifiClientNum";
    public static final String IS_HFP_ON = "isHfpOn";
    public static final String IS_HEALTH_ON = "isHealthOn";
    public static final String IS_A2DP_ON = "isA2dpOn";
    public static final String RES_TYPE = "restype";

    private Context mainContext;
    private boolean scanning;

   // private WifiManager wm;
    private WifiApManager apm;
    //private BluetoothManager btm;
    private BluetoothAdapter btadapter;
    //private BatteryManager bm;


    //private ArrayList<String> restypes;
    private JSONArray restypes;

    //indexes
    private int clientNum;

    private int batLevel;
    private int maxLevel;
    private boolean A2dpOn;
    private boolean HealthOn;
    private boolean HfpOn;

    //private int btconnected;
    // this information will be obtained from proprietary BT channel for Iotivity


    public InfoCenter(Context context, WifiApManager _apm, BluetoothAdapter _btm){

        mainContext = context;
        //restypes = new ArrayList<String>();
        restypes = new JSONArray();

        apm = _apm;
        btadapter = _btm;
        //bm = _bm;

        clientNum = 0;
        scanning = A2dpOn = HealthOn = HfpOn = false;
        maxLevel = batLevel = -1;
    }

    public synchronized void setBatlevel(int level){
        batLevel = level;
    }

    public synchronized void setClientNum(int n){
        clientNum = n;
    }

    public synchronized void setMaxBatlevel(int level){
        maxLevel = level;
    }

    public synchronized void setA2dpOn(boolean res){ A2dpOn = res; }
    public synchronized void setHfpOn(boolean res){ HfpOn = res; }
    public synchronized void setHealthOn(boolean res){ HealthOn = res; }


    public synchronized String getInfo(){

        JSONObject json = new JSONObject();

        try {
            json.put(InfoCenter.WIFI_CLIENT_NUM, this.clientNum);
            json.put(InfoCenter.BATLEVEL, this.batLevel);
            json.put(InfoCenter.MAX_BATLEVEL, this.maxLevel);
            json.put(InfoCenter.IS_A2DP_ON, this.A2dpOn);
            json.put(InfoCenter.IS_HFP_ON, this.HfpOn);
            json.put(InfoCenter.IS_HEALTH_ON, this.HealthOn);

            json.put(InfoCenter.RES_TYPE, this.restypes.toString());

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return json.toString();
    }

    public void updateInfo(){

        //Update in another thread
        Runnable task = new Runnable() {
            @Override
            public void run() {

                // Get battery status.
                // Because Intent.ACTION_BATTERY_CHANGED is sticky,
                // you can register for the broadcast with a null receiver
                // which will only get the battery level one time when you call registerReceiver
                Intent batteryIntent = mainContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                try {
                    int tmp1 = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int tmp2 = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

                    setBatlevel(tmp1);
                    setMaxBatlevel(tmp2);

                }catch(NullPointerException e){
                    Log.v(this.getClass().getName(), "fail to update battery info.");
                }
        /*
                rcv = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final String action = intent.getAction();
                        //Battery level change
                        if(action.equals(Intent.ACTION_BATTERY_CHANGED)){
                            batLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        }
                        //Wifi
                        else if(action.equals("")){

                        }
                    }
                };
        */
                // Get ap state;
                if(apm!=null && apm.getWifiApState() == WIFI_AP_STATE.WIFI_AP_STATE_ENABLED) {
                    scanning = true;
                    apm.getClientList( true, 5000,
                            new FinishScanListener() {
                                public void onFinishScan(ArrayList<ClientScanResult> clients){
                                    setClientNum(clients.size());
                                }
                            }
                    );
                    //simple spinning lock
                    while(scanning){}
                }

                // Get bt status
                // BT must have been on to start negotiation.
                if(btadapter != null){
                    //master or slave
                    setA2dpOn(btadapter.getProfileConnectionState(BluetoothProfile.A2DP)== BluetoothProfile.STATE_CONNECTED);
                    setHfpOn(btadapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED);
                    setHealthOn(btadapter.getProfileConnectionState(BluetoothProfile.HEALTH) == BluetoothProfile.STATE_CONNECTED);
                }

                // Get resource type of the Iotivity servers
                // Currently fixed.
                //TODO: make OIC resource type getter
                restypes.put("core.light");
                //restypes.add("core.light");
                //restypes.add("proximity");
                //restypes.add("stepcount");

                Log.v(this.getClass().getName(), "update finished.");
            }
        };

        Thread t = new Thread(task);
        t.start();
    }

}
