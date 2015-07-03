package com.aleph.mtk.btchannel;
/**
 * Created by MTK07942 on 6/26/2015.
 */

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.iotivity.base.ModeType;
import org.iotivity.base.OcException;
import org.iotivity.base.OcHeaderOption;
import org.iotivity.base.OcPlatform;

import org.iotivity.base.OcRepresentation;
import org.iotivity.base.OcResource;
import org.iotivity.base.OcResourceHandle;
import org.iotivity.base.OcResourceRequest;
import org.iotivity.base.OcResourceResponse;
import org.iotivity.base.PlatformConfig;
import org.iotivity.base.QualityOfService;
import org.iotivity.base.ServiceType;

import java.util.ArrayList;
import java.util.HashMap;

public class ProxyService extends Thread {

    public final static String TAG = "ProxyService";
    public final static int POLLING_PERIOD = 5;
    private Handler mHandler;

    HashMap<String, ProxyResource> proxies;
    String restype;
    String interFace;
    int port;

    boolean finding;

    OcPlatform.OnResourceFoundListener resourceFoundListener = new OcPlatform.OnResourceFoundListener() {
        @Override
        public void onResourceFound(OcResource ocResource) {
            String uri = ocResource.getUri();

            if(uri.contains("&host=1")) return;
            printui("found origin resource: " + uri);

            if(proxies!=null && !proxies.isEmpty()) {
                for (String k : proxies.keySet()) {
                    if (k.equalsIgnoreCase(uri)) {
                        printui("resource already has proxy: " + uri);
                        return;
                    }
                }
            }

            // add new resource proxy
            ProxyResource p = new ProxyResource(restype, ocResource, 10, mHandler);
            printui("new proxy resource");
            p.createResource();
            printui("proxy resource registered");
            proxies.put(uri, p);
            printui("resource added: " + uri);

        }
    };

    public ProxyService(String rt, String intf, int p, Handler h){
        proxies = new HashMap<String, ProxyResource>();
        restype = rt;
        interFace = intf;
        port = p;
        mHandler = h;
    }

    public void run(){
        int count = 0;
        printui("ProxyService started");
        finding = true;

        //create platform config
        PlatformConfig cfg = new PlatformConfig(
                ServiceType.IN_PROC,
                ModeType.CLIENT_SERVER,
                "0.0.0.0", // bind to all available interfaces
                port,
                QualityOfService.LOW);
        OcPlatform.Configure(cfg);

        while(finding){
            try{
                OcPlatform.findResource("", "coap://224.0.1.187/oc/core?rt=" + restype, resourceFoundListener);

                Util.sleep(POLLING_PERIOD);

            }catch(OcException e){
                Log.e(TAG, "findResource ERROR");
                stopProxy();
            }

        }
    }

    public void stopProxy(){
        finding = false;
        for(String uri : proxies.keySet()){
            ProxyResource res = proxies.get(uri);
            res.unregisterRes();
            res = null;
        }
    }

    public void printui(String str){
        //System.out.println("Handoff client:" + str);
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("data",str );
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

}
