/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService
    implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    
    private static final String LOG_TAG = SunshineWatchFaceService.class.getSimpleName();
    private static Typeface normalTypeface;
    private static Typeface boldTypeface;

    private static final boolean FORCE_UPDATE = DataLayerListenerService.FORCE_UPDATE;

    private static final String HI_TEMP_KEY = "hi_temp";
    private static final String LO_TEMP_KEY = "lo_temp";
    private static final String WEATHER_ICON_KEY = "icon";
    private static final String TIME_STAMP_KEY = "time_stamp";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /** Alpha value for drawing time when in mute mode. */
    static final int MUTE_ALPHA = 100;

    /** Alpha value for drawing time when not in mute mode. */
    static final int NORMAL_ALPHA = 255;

    static final int MSG_UPDATE_TIME = 0;

    /** How often  ticks in milliseconds. */
    long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

    @Override // GoogleApiClient.ConnectionCallbacks
    public void onConnected(Bundle connectionHint) {
        Log.d(LOG_TAG, "onConnected: " + connectionHint);
    }

    @Override  // GoogleApiClient.ConnectionCallbacks
    public void onConnectionSuspended(int cause) {
        Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
    }

    @Override  // GoogleApiClient.OnConnectionFailedListener
    public void onConnectionFailed(ConnectionResult result) {
        Log.d(LOG_TAG, "onConnectionFailed: " + result);
    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        static final String COLON_STRING = ":";

        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mAmPmPaint;
        Paint mHiTempPaint;
        Paint mLoTempPaint;
        Paint mColonPaint;
        Paint mTempPanelPaint;
        Paint mWeatherIconPaint;
        float mColonWidth;
        float mTimeOffset;
        float mDateOffset;
        float mTempOffset;
        float mAmPmOffset;
        float mHiTempOffset;
        float mLoTempOffset;
        float mHorizDividerOffset;
        boolean mShouldDrawColons;
        boolean mAmbient;
        boolean mMute;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;
        DataMap mForecast;
        DataMap lastForecast;

        private Double hi;
        private Double lo;
        private long timeStamp;
        private Bitmap weatherIcon;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        String mAmString;
        String mPmString;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            // Don't think this should be necessary, but DataLayerListenerService seems to never start
            Intent intent = new Intent(SunshineWatchFaceService.this, DataLayerListenerService.class);
            startService(intent);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            Context context = SunshineWatchFaceService.this.getBaseContext();

            normalTypeface =
                    Typeface.createFromAsset(getAssets(), "fonts/RobotoCondensed-Light.ttf");
            boldTypeface =
                    Typeface.createFromAsset(getAssets(), "fonts/RobotoCondensed-Regular.ttf");

            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(context, R.color.background));

            // Define the various Paints used
            mDatePaint = createTextPaint(ContextCompat.getColor(context, R.color.dim_text));
            mHourPaint = createTextPaint(ContextCompat.getColor(context, R.color.bright_text),
                    boldTypeface);
            mMinutePaint = createTextPaint(ContextCompat.getColor(context, R.color.bright_text));
            mAmPmPaint = createTextPaint(ContextCompat.getColor(context, R.color.dim_text));
            mColonPaint = createTextPaint(ContextCompat.getColor(context, R.color.dim_text));
            mHiTempPaint = createTextPaint(ContextCompat.getColor(context, R.color.bright_text),
                    boldTypeface);
            mLoTempPaint = createTextPaint(ContextCompat.getColor(context, R.color.dim_text));
            mTempPanelPaint = createTextPaint(ContextCompat.getColor(context,
                    R.color.temp_panel_background));

            // Set text sizes for time, date, temp
            mHourPaint.setTextSize(resources.getDimension(R.dimen.digital_time_size));
            mMinutePaint.setTextSize(resources.getDimension(R.dimen.digital_time_size));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_size));
            mColonPaint.setTextSize(resources.getDimension(R.dimen.digital_time_size));
            mHiTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_size));
            mLoTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_size));
            mAmPmPaint.setTextSize(resources.getDimension(R.dimen.digital_ampm_size));

            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mHiTempPaint.setTextAlign(Paint.Align.RIGHT);

            mColonWidth = mColonPaint.measureText(COLON_STRING);

            mWeatherIconPaint = new Paint();
            mWeatherIconPaint.setAntiAlias(true);
            mWeatherIconPaint.setFilterBitmap(true);

            // Vertical offsets, relative to horizontal midline
            mTimeOffset = resources.getDimension(R.dimen.time_offset);
            mDateOffset = resources.getDimension(R.dimen.date_offset);
            mTempOffset = resources.getDimension(R.dimen.temp_offset);
            mAmPmOffset = resources.getDimension(R.dimen.am_pm_offset);
            mHorizDividerOffset = resources.getDimension(R.dimen.horiz_divider_offset);
            mHiTempOffset = resources.getDimension(R.dimen.hi_temp_offset);
            mLoTempOffset = resources.getDimension(R.dimen.lo_temp_offset);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, normalTypeface);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mAmPmPaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            Log.d(LOG_TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mAmPmPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            Context context = SunshineWatchFaceService.this.getBaseContext();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(ContextCompat.getColor(context,
                            mTapCount % 2 == 0 ? R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getDateFormat(SunshineWatchFaceService.this);
            mDateFormat.setCalendar(mCalendar);
        }

        /* This method is taken from the sample watch face DigitalWatchFaceService */
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            float midX = bounds.width() / 2;   // X coordinate of midline (vertical)
            float midY = bounds.height() / 2;  // Y coordinate of midline (horizontal)

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);
            if (!is24Hour) {
                midX -= mAmPmOffset;
            }

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw gray rectangle behind weather info
            canvas.drawRect(0, midY + mHorizDividerOffset,
                    bounds.width(), bounds.height(), mTempPanelPaint);

            // Set colons at middle of the screen (bounds.width()/2 - width of colon)
            // Draw the hours, relative to left side of colon
            mXOffset = midX - mColonWidth / 2;
            mYOffset = midY - mTimeOffset;

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, mXOffset, mYOffset, mColonPaint);
            }

            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }

            mXOffset -= mHourPaint.measureText(hourString);
            canvas.drawText(hourString, mXOffset, mYOffset, mHourPaint);
            mXOffset = midX + mColonWidth / 2;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
             canvas.drawText(minuteString, mXOffset, mYOffset, mMinutePaint);
            mXOffset += mMinutePaint.measureText(minuteString) + 4f;

            // If we're in 12-hour mode, draw AM/PM
            // TODO: rotate AM/PM 90 deg, end at baseline
            if (!is24Hour) {
                // Log.d(LOG_TAG, "AmPmString mXOffset: " + mXOffset);
                canvas.drawText(getAmPmString(
                        mCalendar.get(Calendar.AM_PM)), mXOffset, mYOffset, mAmPmPaint);
            }

            // Draw date string
            midX = bounds.width() / 2;  // reset to center line
            String dateString = mDayOfWeekFormat.format(mDate);
            canvas.drawText(dateString, midX, midY - mDateOffset, mDatePaint);

            // Draw forecast info
            parseForecast();
            if (mForecast != null) {  // gets Hi and Lo temps, timestamp and weather icon

                String hiTemp;
                String loTemp;

                // Draw weather icon
                // TODO: define destination rectangle in dimens.xlm
                Rect destRect = new Rect(Math.round(midX - 50),
                        Math.round(midY + mHorizDividerOffset + 4),
                        Math.round(midX + 50),
                        Math.round(midY + mHorizDividerOffset + 104));

                // Draw weather icon
                if (weatherIcon == null) {
                    canvas.drawBitmap(fakeWeatherIcon(), null, destRect, null);
                } else {   // Replace with the following when Message is working
                    canvas.drawBitmap(weatherIcon, null, destRect, null);
                }

                if (hi != null) {
                    hiTemp = getString(R.string.format_temperature, hi);
                    loTemp = getString(R.string.format_temperature, lo);
                    Log.d(LOG_TAG, "hiTemp: " + hiTemp + "; loTemp: " + loTemp);
                } else { // temporarily display placeholders if no data available
                    hiTemp = "Hi\u00B0";
                    loTemp = "Lo\u00B0";
                }

                // Hi temperature
                mYOffset = midY + mTempOffset;
                mXOffset = midX - mLoTempOffset;
                canvas.drawText(hiTemp, mXOffset, mYOffset, mHiTempPaint);
                // Lo temperature
                mXOffset = midX + mLoTempOffset;
                canvas.drawText(loTemp, mXOffset, mYOffset, mLoTempPaint);

            }
        }

        /* Get forecast info from DataLayerListenerService */
        boolean parseForecast() {  
            
            mForecast = DataLayerListenerService.getForecast();

            // If forecast hasn't changed, return false.  Current implementation has a timestamp,
            // so should always be true.
            if (mForecast == null) {
                Log.d(LOG_TAG, "Forecast: null");

                return false;
            }
            else if (lastForecast != null &&  mForecast.equals(lastForecast)) {
                Log.d(LOG_TAG, "Forecast unchanged");
                return false;
            }

            lastForecast = mForecast;
            
            hi = mForecast.getDouble(HI_TEMP_KEY);
            lo = mForecast.getDouble(LO_TEMP_KEY);
            if (FORCE_UPDATE) {
                timeStamp = mForecast.getLong(TIME_STAMP_KEY);
            }
            byte [] weatherIconByteArray = mForecast.getByteArray(WEATHER_ICON_KEY);
            weatherIcon = getWeatherIcon(weatherIconByteArray);
    
            Log.d(LOG_TAG,"in parseForecast, Hi: "+hi+"; Lo: "+lo+"Timestamp: "+timeStamp);
            return true;
        }

        private Bitmap getWeatherIcon(byte[] byteArray) {
            return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        // Provides a dummy weather icon for debugging
        Bitmap fakeWeatherIcon() {
            return BitmapFactory.decodeResource(getResources(), R.drawable.art_rain);
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
