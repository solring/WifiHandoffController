package com.aleph.mtk.btchannel;

import android.os.Handler;

/**
 * Created by MTK07942 on 6/30/2015.
 */
public class Util {
    public static void sleep(int sec){
        try {
            Thread.sleep(sec * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void waitOIC(){
        this.sleep(10);
    }


}
