package com.example.android.sunshine.app;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

/**
 * Listens to DataItems and Messages from the local node.
 */
public class DataLayerListenerService extends WearableListenerService implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        CapabilityApi.CapabilityListener {

    private static final String LOG_TAG = DataLayerListenerService.class.getSimpleName();

    /* Data layer strings as sent by SunshineSyncAdapter */
    private static final String FORECAST_PATH =  "/forecast";
    private static final String TIME_STAMP_KEY = "time_stamp";
    private static final String DATA_ITEM_RECEIVED_PATH = "/data-item-received";

    // Used to search for the capability to launch the sync adapter on wearable
    private static final String FORECAST_CAPABILITY = "forecast";

    // Forces constant updates if true.  Must also be set in watch.DataLayerListenerService
    public  static final boolean FORCE_UPDATE = true;

    private static DataMap mForecast;
    private static String forecastNodeId;

    private static GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "In onCreate()");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        mGoogleApiClient.connect();

        requestForecast();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(LOG_TAG, "Google API Client was connected");
        Wearable.DataApi.addListener(mGoogleApiClient, this);
        Wearable.MessageApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.e(LOG_TAG, "Connection to Google API client has failed");
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        Wearable.MessageApi.removeListener(mGoogleApiClient, this);
    }

    //
    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(LOG_TAG, "CapabilityInfo: " + capabilityInfo.toString());
        requestForecast();
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(LOG_TAG, "onDataChanged: " + dataEvents);

        if (!mGoogleApiClient.isConnected() && !mGoogleApiClient.isConnecting()) {
            // This blocking call is causing an IllegalStateException: "blockingConnect must not be
            // called on the UI thread", yet documentation states that a WearableListenerService
            // runs on a background thread.
            // ConnectionResult connectionResult = mGoogleApiClient
            //        .blockingConnect(30, TimeUnit.SECONDS);
            // if (!connectionResult.isSuccess()) {
            Log.e(LOG_TAG, "In onDataChanged, GoogleApiClient not connected.");
            return;
        }

        // Loop through the events and send a message back to the node that created the data item.
        Log.d(LOG_TAG, "Number of DataEvents: " + dataEvents.getCount());
        for (DataEvent event : dataEvents) {

            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();
            Log.d(LOG_TAG, "URI path: " + path);
            if (FORECAST_PATH.equals(path)) {
                // Get the node id of the node that created the data item from the host portion of
                // the uri.
                String nodeId = uri.getHost();
                Log.d(LOG_TAG, "NodeId from DataItem: " + nodeId);

                // Extract forecast DataMap from payload
                DataItem item = event.getDataItem();
                mForecast = DataMapItem.fromDataItem(item).getDataMap();

                long mTimeStamp = -1;
                if (FORCE_UPDATE) {
                    mTimeStamp = mForecast.getLong(TIME_STAMP_KEY);
                }
                // Send the confirmation
                Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, DATA_ITEM_RECEIVED_PATH,
                        longToBytes(mTimeStamp));
            }
        }
    }

    @Override
    public void onConnectedNodes(List<Node> connectedNodes) {
        // After we are notified by this callback, we need to query for the nodes that provide the
        // forecast capability and are directly connected.
        if (mGoogleApiClient.isConnected()) {
            setOrUpdateForecastConnection();
        } else if (!mGoogleApiClient.isConnecting()) {
            mGoogleApiClient.connect();
        }
    }

    private static void setOrUpdateForecastConnection() {

        if (mGoogleApiClient == null) {  // assume it's connecting, wait for next call
            Log.d(LOG_TAG, "GoogleApiClient not yet connected in setOrUpdateForecastConnection()");
            return;
        }
        Wearable.CapabilityApi.getCapability(
            mGoogleApiClient, FORECAST_CAPABILITY,
            CapabilityApi.FILTER_REACHABLE).setResultCallback(
            new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                @Override
                public void onResult(CapabilityApi.GetCapabilityResult result) {
                    if (result.getStatus().isSuccess()) {
                        Log.d(LOG_TAG, "Capability: " + result.getCapability().getName());
                        updateForecastCapability(result.getCapability());
                    } else {
                        Log.e(LOG_TAG,
                                "setOrUpdateForecastConnection() Failed to get capabilities, "
                                        + "status: "
                                        + result.getStatus().getStatusMessage());
                    }
                }
            }
        );
    }

    private static void updateForecastCapability(CapabilityInfo capabilityInfo) {
        Set<Node> connectedNodes = capabilityInfo.getNodes();
        if (connectedNodes.size() == 0) {
            Log.d(LOG_TAG, "No connected nodes found.");
        }
        for (Node node : connectedNodes) {
            Log.d(LOG_TAG, "Node: " + node.toString());
            // we are only considering those nodes that are directly connected
            if (node.isNearby()) {
                Log.d(LOG_TAG, "Found node: " + node.toString());
                forecastNodeId = node.getId();
            }
        }
    }

    // Sends a message to SunshineAsyncAdapter requesting a forecast message
    public static void requestForecast() {

        if (mGoogleApiClient == null) {  // assume it's connecting, wait for next call
            Log.d(LOG_TAG, "GoogleApiClient not yet connected");
            return;
        }

        if (forecastNodeId == null) {
            Log.d(LOG_TAG, "No node found with forecast capability");
            setOrUpdateForecastConnection();
        }

        Wearable.MessageApi.sendMessage(mGoogleApiClient, forecastNodeId,
                FORECAST_PATH, FORECAST_CAPABILITY.getBytes());
        Log.d(LOG_TAG, "Message sent requesting forecast with nodeId: " + forecastNodeId);
    }

    // Convert long to Byte array
    private byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    // Send forecast DataMap
    public static DataMap getForecast() {

        if (mForecast == null) {
            requestForecast();
            return null;  // Catch result on next tick??
        }
        return mForecast;
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d(LOG_TAG, "onMessageReceived: " + messageEvent);

        // Check to see if the message confirming message receipt
        if (messageEvent.getPath().equals(DATA_ITEM_RECEIVED_PATH)) {
            Log.d(LOG_TAG, "Message received");
        }
    }
}