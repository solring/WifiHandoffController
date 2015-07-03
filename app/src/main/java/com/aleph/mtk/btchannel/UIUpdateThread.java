package com.aleph.mtk.btchannel;

import android.app.AlarmManager;
import android.util.Log;

import com.whitebyte.wifihotspotutils.ClientScanResult;
import com.whitebyte.wifihotspotutils.FinishScanListener;
import com.whitebyte.wifihotspotutils.WifiApManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by MTK07942 on 6/10/2015.
 */
public class UIUpdateThread extends Thread {

    private static int DEFAULT_PERIOD = 5; //5 sec.
    private static String TAG = "UIUpdateThread";

    private boolean stop;
    private Lock lock;
    private Condition done;
    private boolean ready;

    private WifiApManager apManager;
    private ClientListAdapter clAdapter;
    private int period;

    public UIUpdateThread(WifiApManager am, ClientListAdapter adapter, int sec){
        clAdapter = adapter;
        apManager = am;
        stop = false;
        period = sec;
        lock = new ReentrantLock();
        done = lock.newCondition();
    }

    public UIUpdateThread(WifiApManager am, ClientListAdapter adapter){
        clAdapter = adapter;
        apManager = am;
        stop = false;
        period = DEFAULT_PERIOD;
        lock = new ReentrantLock();
        done = lock.newCondition();
    }

    public void run(){
        runMTK();
    }


    public void runNormal(){

        Log.v(TAG, "UP update thread started");
        while(!stop){

            Log.v(TAG, "scan wifi AP clients...");
            ready = false;
            apManager.getClientList(false, new FinishScanListener() {
                @Override
                public void onFinishScan(final ArrayList<ClientScanResult> clients) {
                    String ip, mac, name;

                    ArrayList<ClientListAdapter.APClient> list = new ArrayList<ClientListAdapter.APClient>();

                    Log.v(TAG, "--- update AP client list ---");
                    for (ClientScanResult c : clients) {
                        ip = c.getIpAddr();
                        mac = c.getHWAddr();
                        name = c.getDevice();

                        Log.v(TAG, "  ip=" + ip + ", mac=" + mac + ", device=" + name);

                        list.add(new ClientListAdapter.APClient(mac));
                    }
                    clAdapter.addItems(list);
                    clAdapter.updateListView();

                    lock.lock();
                    done.signal();
                    lock.unlock();

                    //ready = true
                }
            });

            //lock
            //while(!ready){}
            lock.lock();
            try {
                done.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }finally {
                lock.unlock();
            }

            Util.sleep(period);
        }
    }

    public void runMTK(){

        Log.v(TAG, "UI update thread started");
        while(!stop){

            Log.v(TAG, "scan wifi AP clients...");
            ready = false;

            ArrayList<ClientListAdapter.APClient> list = new ArrayList<ClientListAdapter.APClient>();
            List<String> clients = apManager.getClientListMTK();
            if(clients!=null) {
                for (String c : clients) {
                    list.add(new ClientListAdapter.APClient(c));
                }
                clAdapter.addItems(list);
                clAdapter.updateListView();
            }

            //sleep for period
            Util.sleep(period);
        }
        Log.v(TAG, "UI update thread stoped");
    }

    public void cancel(){
        stop = true;
    }

    public void setPeriod(int sec){
        period = sec;
    }

    public void resetPeriod(){
        period = DEFAULT_PERIOD;
    }
}
