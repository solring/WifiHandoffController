package com.aleph.mtk.btchannel;

/**
 * Created by MTK07942 on 6/26/2015.
 */

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import org.iotivity.base.EntityHandlerResult;
import org.iotivity.base.ModeType;
import org.iotivity.base.ObservationInfo;
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
import org.iotivity.base.RequestHandlerFlag;
import org.iotivity.base.RequestType;
import org.iotivity.base.ResourceProperty;
import org.iotivity.base.ServiceType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;


public class ProxyResource extends Service {

    public final static double DEFAULT_EXPIRE = 500;
    //public final static double DEFAULT_EXPIRE = 15000; //15 sec
    public final static String TAG = "ProxyResource";
    public static final int ERROR_CODE = 200;

    private Handler uihandler;

    private OcResourceHandle mResourceHandle;
    private OcResource origin;
    private OcRepresentation lastGetRep;
    private String type;
    private String Uri;
    private String originUri;
    private String originHost;

    boolean observating = false;

    double expireDuration;

    double now;
    double lastGetTime;
    double lastOnGetTime;

    private ArrayList<OcResourceRequest> putQ;
    private ArrayList<OcResourceRequest> postQ;
    private ArrayList<OcResourceRequest> getQ;


    public ProxyResource(){
        lastGetRep = new OcRepresentation();
        lastGetRep.setUri("");
        now = lastGetTime = lastOnGetTime = -1;
        expireDuration = DEFAULT_EXPIRE;

        putQ = new ArrayList<OcResourceRequest>();
        postQ = new ArrayList<OcResourceRequest>();
        getQ = new ArrayList<OcResourceRequest>();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public ProxyResource(String _type, OcResource resource, double expireSec, Handler h){
        this();

        type = _type;
        origin = resource;
        originUri = resource.getUri();
        //originHost = resource.getHost();
        expireDuration = expireSec * 1000;

        uihandler = h;

        observating = false;
        Uri = originUri + "&host=1";

    }


    public OcResourceHandle createResource()
    {
        Log.d(TAG, "createResource");
        OcPlatform.EntityHandler eh = new OcPlatform.EntityHandler() {
            @Override
            public EntityHandlerResult handleEntity(OcResourceRequest ocResourceRequest) {
                // this is where the main logic of simpleserver is handled as different requests (GET, PUT, POST, OBSERVE, DELETE) are handled
                return entityHandler(ocResourceRequest);
            }
        };
        if(eh==null){
            logMessage("new EntityHandler fail!");
            return null;
        }

        try {
            Log.d(TAG, "Registered resource Uri:" + Uri + ", type: " + type + ", res: " + eh);
            mResourceHandle = OcPlatform.registerResource(Uri, type,
                    OcPlatform.DEFAULT_INTERFACE, eh,
                    EnumSet.of(ResourceProperty.DISCOVERABLE, ResourceProperty.OBSERVABLE));
            logMessage("Successfully registered resource " + Uri);
        } catch (OcException e) {
            logMessage("RegisterResource ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        return mResourceHandle;

    }

    public void unregisterRes() {

        try {
            OcPlatform.unregisterResource(mResourceHandle);
        } catch (OcException e) {
            Log.e(TAG, "unregisterResource ERROR:" + e.getMessage());
        }
    }

    public OcResource getResource(){
        return origin;
    }

    /****************************************************************************
     * this is the main method which handles different incoming requests appropriately.
     * Init is not supported currently.
     * @param request OcResourceRequest from the client
     * @return EntityHandlerResult depending on whether the request was handled successfully or not
     ****************************************************************************/
    private EntityHandlerResult entityHandler(OcResourceRequest request) {
        //logMessage("Request received in entityHandler");
        EntityHandlerResult result = EntityHandlerResult.ERROR;
        if (null != request) {
            RequestType requestType = request.getRequestType();
            EnumSet<RequestHandlerFlag> requestFlag = request.getRequestHandlerFlagSet();

            if (requestFlag.contains(RequestHandlerFlag.INIT)) {
                logMessage(TAG + "Init");
            }
            if (requestFlag.contains(RequestHandlerFlag.REQUEST)) {
                switch (requestType) {
                    // handle GET request
                    case GET:
                        //logMessage("GET");
                        getQ.add(request);
                        get();
                        break;
                    // handle PUT request
                    case PUT:
                        //logMessage(TAG + "PUT");
                        OcRepresentation rep = request.getResourceRepresentation();
                        putQ.add(request);
                        put(rep);
                        break;
                    // handle POST request
                    case POST:
                        //logMessage(TAG + "POST");
                        rep = request.getResourceRepresentation();
                        postQ.add(request);
                        post(rep);
                        break;
                    // handle DELETE request
                    case DELETE:
                        //logMessage(TAG + "DELETE");
                        deleteRes();
                        break;
                }

                result = EntityHandlerResult.OK;
            }
            // handle OBSERVER request
            /*
            if (requestFlag.contains(RequestHandlerFlag.OBSERVER)) {
                logMessage(TAG + "OBSERVER");
                ObservationInfo observationInfo = request.getObservationInfo();
                switch (observationInfo.getObserveAction()) {
                    case REGISTER:
                        synchronized (mObservationIds) {
                            mObservationIds.add(observationInfo.getOcObservationId());
                            logMessage("Register observer");
                        }
                        break;
                    case UNREGISTER:
                        synchronized (mObservationIds) {
                            mObservationIds.remove(observationInfo.getOcObservationId());
                            logMessage("Unregister observer");
                        }
                        break;
                }
                if (null == lightRepThread) {
                    lightRepThread = new LightRepThread(this, mObservationIds);
                    lightRepThread.run();
                }
                result = EntityHandlerResult.OK;
            }
            */
        }
        return result;
    }

    /**************** GET ****************/
    public void get()
    {
        if(origin!=null){

            logMessage("------------ onGet --------------\n");

            now = System.currentTimeMillis();

            if(lastGetTime!=-1){
                double diff = now - lastGetTime;
                Log.d(TAG, "diff=" + diff + ", expire=" + expireDuration);

            }

            if(lastGetTime!=-1 && (now - lastGetTime) < expireDuration){ //Last value is not expired
                // Send old
                Log.d(TAG, "last value not expired, send old value");
                if(lastOnGetTime!=-1)
                    sendGetResponses(lastGetRep, 0);
            }
            else{
                //
                try {

                    OcResource.OnGetListener onGetListener = new OcResource.OnGetListener() {
                        public int count = 0;
                        @Override
                        public void onGetCompleted(List<OcHeaderOption> headerOptionList, OcRepresentation rep) {
                            Log.d(TAG, "------------ onGetCompleted --------------\n");
                            lastGetRep = rep;
                            sendGetResponses(lastGetRep, 0);
                        }
                    };
                    origin.get(new HashMap<String, String>(), onGetListener);
                    lastGetTime = now;

                } catch (OcException e) {
                    logMessage("get resource fail from "+Uri);
                    e.printStackTrace();
                }
            }

        }
    }

    /***********************************************************
     * Helper function for onGetCompleted
     * Send the responses from the origin resource to clients
     **********************************************************/
    private void sendGetResponses(OcRepresentation rep, int eCode){

        OcRepresentation tmp = rep;
        OcResourceResponse ocResourceResponse = new OcResourceResponse();
        ocResourceResponse.setResponseResult(EntityHandlerResult.OK);
        ocResourceResponse.setErrorCode(ERROR_CODE);
        ocResourceResponse.setResourceRepresentation(tmp);

        //response to every request in the GET queue
        for(OcResourceRequest req : getQ) {

            ocResourceResponse.setRequestHandle(req.getRequestHandle());
            ocResourceResponse.setResourceHandle(req.getResourceHandle());

            int retry = 0;
            while(retry < 5){

                try {
                    OcPlatform.sendResponse(ocResourceResponse);
                    break;
                }catch (OcException e) {
                    logMessage("send get response fail.");
                    e.printStackTrace();
                }
                retry++;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        getQ.clear();

        lastOnGetTime = System.currentTimeMillis();
    }

    /**************** PUT ****************/
    public void put(final OcRepresentation rep)
    {
        OcResource.OnPutListener onPutListener = new OcResource.OnPutListener(){

            @Override
            public void onPutCompleted(List<OcHeaderOption> list, OcRepresentation ocRepresentation) {
                logMessage("------------ onPut --------------\n");
                //Log.d(TAG, "origin URI: " + rep.getUri());

                OcRepresentation tmp = rep;
                OcResourceResponse ocResourceResponse = new OcResourceResponse();
                ocResourceResponse.setResponseResult(EntityHandlerResult.OK);
                ocResourceResponse.setErrorCode(ERROR_CODE);

                OcResourceRequest req = putQ.remove(0);
                if(rep!=null){
                    ocResourceResponse.setRequestHandle(req.getRequestHandle());
                    ocResourceResponse.setResourceHandle(req.getResourceHandle());
                    ocResourceResponse.setResourceRepresentation(tmp);
                    try{
                        OcPlatform.sendResponse(ocResourceResponse);
                    }catch(OcException e ){
                        logMessage(TAG + "OnPut Error " + e.getMessage());
                    }
                }
            }

        };

        if(origin!=null){
            try {
                origin.put(rep, new HashMap<String, String>(), onPutListener);
            }catch (OcException e) {
                logMessage(TAG + "put fail from " + rep.getUri());
                e.printStackTrace();
            }
        }
    }

    /**************** POST ****************/
    public void post(final OcRepresentation rep)
    {
        OcResource.OnPostListener onPostListener = new OcResource.OnPostListener(){

            @Override
            public void onPostCompleted(List<OcHeaderOption> list, OcRepresentation ocRepresentation) {
                logMessage("------------ onPut --------------\n");
                //Log.d(TAG, "origin URI: " + rep.getUri());

                OcRepresentation tmp = rep;
                OcResourceResponse ocResourceResponse = new OcResourceResponse();
                ocResourceResponse.setResponseResult(EntityHandlerResult.OK);
                ocResourceResponse.setErrorCode(ERROR_CODE);

                OcResourceRequest req = putQ.remove(0);
                if(rep!=null){
                    ocResourceResponse.setRequestHandle(req.getRequestHandle());
                    ocResourceResponse.setResourceHandle(req.getResourceHandle());
                    ocResourceResponse.setResourceRepresentation(tmp);
                    try{
                        OcPlatform.sendResponse(ocResourceResponse);
                    }catch(OcException e ){
                        logMessage(TAG + "OnPstt Error " + e.getMessage());
                    }
                }
            }

        };

        if(origin!=null){
            try {
                origin.post(rep, new HashMap<String, String>(), onPostListener);
            }catch (OcException e) {
                logMessage(TAG + "post fail from " + rep.getUri());
                e.printStackTrace();
            }
        }
    }

    /******************** DELETE ********************/
    public void deleteRes(){
        logMessage("---------------- onDelete -----------------");
        OcResource.OnDeleteListener onDeleteListener = new OcResource.OnDeleteListener(){

            @Override
            public void onDeleteCompleted(List<OcHeaderOption> list) {
                try {
                    OcPlatform.unregisterResource(mResourceHandle);
                } catch (OcException e) {
                    logMessage("Unregister local resource failed.");
                    e.printStackTrace();
                }
            }
        };
        if(origin!=null){
            origin.deleteResource(onDeleteListener);
        }
    }

    /************* UI helper functions **************/
    public void logMessage(String text) {
        Message msg = new Message();
        Bundle data = new Bundle();
        data.putString("data", text);
        msg.setData(data);
        uihandler.sendMessage(msg);
    }

}
