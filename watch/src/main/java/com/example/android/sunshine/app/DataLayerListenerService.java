package com.example.android.sunshine.app;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Listens to DataItems and Messages from the local node.
 */
public class DataLayerListenerService extends WearableListenerService {

    private static final String LOG_TAG = DataLayerListenerService.class.getSimpleName();

    /* Data layer strings as sent by SunshineSyncAdapter */
    private static final String FORECAST_PATH =  "/forecast";
    private static final String TIME_STAMP_KEY = "time_stamp";
    private static final String DATA_ITEM_RECEIVED_PATH = "/data-item-received";
    // Forces constant updates if true.  Must also be set in watch.DataLayerListenerService
    public  static final boolean FORCE_UPDATE = true;

    private static DataMap mForecast;

    GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
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
        for (DataEvent event : dataEvents) {
            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();
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

    // Convert long to Byte array
    private byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    // Send forecast DataMap
    public static DataMap getForecast() {
        return mForecast;
    }

//    @Override
//    public void onMessageReceived(MessageEvent messageEvent) {
//        Log.d(LOG_TAG, "onMessageReceived: " + messageEvent);
//
//        // Check to see if the message is to start an activity
//        if (messageEvent.getPath().equals(START_ACTIVITY_PATH)) {
//            Intent startIntent = new Intent(this, MainActivity.class);
//            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            startActivity(startIntent);
//        }
//    }
}