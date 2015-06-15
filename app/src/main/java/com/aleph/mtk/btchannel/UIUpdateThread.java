package com.aleph.mtk.btchannel;

import android.util.Log;

import com.whitebyte.wifihotspotutils.ClientScanResult;
import com.whitebyte.wifihotspotutils.FinishScanListener;
import com.whitebyte.wifihotspotutils.WifiApManager;

import java.util.ArrayList;

/**
 * Created by MTK07942 on 6/10/2015.
 */
public class UIUpdateThread extends Thread {

    private static int DEFAULT_PERIOD = 5000; //5 sec.
    private static String TAG = "UIUpdateThread";

    private boolean stop;
    private WifiApManager apManager;
    private ClientListAdapter clAdapter;

    public UIUpdateThread(WifiApManager am, ClientListAdapter adapter){
        clAdapter = adapter;
        apManager = am;
        stop = false;
    }

    public void run(){
        Log.v(TAG, "UP update thread started");
        while(!stop){
            Log.v(TAG, "scan wifi AP clients...");
            apManager.getClientList(false, new FinishScanListener() {
                @Override
                public void onFinishScan(final ArrayList<ClientScanResult> clients) {
                    String ip, mac, name;

                    ArrayList<ClientListAdapter.APClient> list = new ArrayList<ClientListAdapter.APClient>();
                    for (ClientScanResult c : clients) {
                        ip = c.getIpAddr();
                        mac = c.getHWAddr();
                        name = c.getDevice();

                        Log.v(TAG, "--- update AP client list ---");
                        Log.v(TAG, "  ip=" + ip + ", mac=" + mac + ", device=" + name);

                        list.add(new ClientListAdapter.APClient(mac, ip));
                    }

                    clAdapter.addItems(list);
                    clAdapter.updateListView();
                }
            });

            try {
                Thread.sleep(DEFAULT_PERIOD);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void cancel(){
        stop = true;
    }

}
