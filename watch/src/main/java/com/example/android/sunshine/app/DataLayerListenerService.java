package com.example.android.sunshine.app;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private static String syncNodeId;

    private GoogleApiClient mGoogleApiClient;

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

        RequestForecast requestForecast = new RequestForecast();
        requestForecast.execute();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(LOG_TAG, "Google API Client was connected");
        Wearable.DataApi.addListener(mGoogleApiClient, this);
        Wearable.MessageApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(LOG_TAG, "Connection to Google API client was suspended");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.e(LOG_TAG, "Connection to Google API client has failed");
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        Wearable.MessageApi.removeListener(mGoogleApiClient, this);
    }

    // TODO: set up a CapabilityChangedListener
    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(LOG_TAG, "CapabilityInfo: " + capabilityInfo.toString());
        RequestForecast requestForecast = new RequestForecast();
        requestForecast.execute();

    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(LOG_TAG, "onDataChanged: " + dataEvents);

        if (!mGoogleApiClient.isConnected() || !mGoogleApiClient.isConnecting()) {
            ConnectionResult connectionResult = mGoogleApiClient
                    .blockingConnect(30, TimeUnit.SECONDS);
            if (!connectionResult.isSuccess()) {
                Log.e(LOG_TAG, "DataLayerListenerService failed to connect to GoogleApiClient, "
                        + "error code: " + connectionResult.getErrorCode());
                return;
            }
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

    // Look for node with sync capability (should be handheld running Sunshine app)
    // and get
    private class RequestForecast extends AsyncTask<Void, Void, String> {

        protected String doInBackground(Void... params) {

            CapabilityApi.GetCapabilityResult result =
                    Wearable.CapabilityApi.getCapability(mGoogleApiClient, FORECAST_CAPABILITY,
                            CapabilityApi.FILTER_REACHABLE).await();

            // Get best node (expect only one with this capability)
            Set<Node> connectedNodes = result.getCapability().getNodes();
            Log.d(LOG_TAG, "Number of connected nodes: " + connectedNodes.size());
            if (connectedNodes.size() == 0) {
                Log.d(LOG_TAG, "Result: " + result.getCapability().getName());
            }
            syncNodeId = null;
            for (Node node : connectedNodes) {
                Log.d(LOG_TAG, "Node: " + node.toString());
                if (node.isNearby()) {
                    Log.d(LOG_TAG, "Found node: " + node.toString());
                    syncNodeId = node.getId();
                }
            }
            return syncNodeId;
        }

        protected void onPostExecute(String syncNodeId) {
            if (syncNodeId == null) {
                Log.d(LOG_TAG, "No node found with sync capability");
                return;
            } else {
                Wearable.MessageApi.sendMessage(mGoogleApiClient, syncNodeId,
                        FORECAST_PATH, FORECAST_CAPABILITY.getBytes());
                Log.d(LOG_TAG, "Message sent requesting forecast");
            }

        }
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