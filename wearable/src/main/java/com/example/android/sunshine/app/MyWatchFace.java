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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.R.attr.centerX;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final String LOG_TAG  = MyWatchFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mTempHighPaint;
        Paint mLowPaint;
        boolean mAmbient;
        Calendar mCalendar;
        String mDay;
        String mMonth;
        String mMonthDate;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        GoogleApiClient mGoogleApiClient;
        private String mHighTemp;
        private String mLowTemp;
        private Bitmap mIcon;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            //mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            //mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.date_text));
            mCalendar = Calendar.getInstance();

            // Much thanks http://stackoverflow.com/a/23266601/3455743 :)
            mDay = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            mMonth = mCalendar.getDisplayName(Calendar.MONTH,Calendar.SHORT,Locale.getDefault());
            mMonthDate = mCalendar.getDisplayName(Calendar.DAY_OF_MONTH, Calendar.SHORT, Locale.getDefault());


            // Create instance of GoogleAPIClient
            mGoogleApiClient = new GoogleApiClient.Builder(getBaseContext()).addApi(Wearable.API)
                    .addOnConnectionFailedListener(this)
                    .addConnectionCallbacks(this)
                    .build();

            // We should connect it here as onCreate does most of the heavy lifting and rest is just canvas updation
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(R.dimen.digital_date_size);
            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateTextSize);
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE));
            //Toast.makeText(MyWatchFace.this, text, Toast.LENGTH_SHORT).show();
            //Log.d(LOG_TAG,text);

            float centerX = bounds.centerX();
            float centerY = bounds.centerY();

            String time_day =  String.format(Locale.ENGLISH,"%d:%02d", mCalendar.getTime().getHours(), mCalendar.getTime().getMinutes());
            float timeTextSize = mTextPaint.measureText(time_day);
            canvas.drawText(time_day, centerX - timeTextSize/2, mYOffset - timeTextSize/6, mTextPaint);
            String dateText = mDay + "," + mMonth + " " + mCalendar.get(Calendar.DAY_OF_MONTH);
            float dateTextSize = mDatePaint.measureText(dateText);
            float dateYOffset = mYOffset + getResources().getDimension(R.dimen.digital_time_text_margin_bottom);
            canvas.drawText(dateText.toUpperCase(), centerX - dateTextSize / 2, dateYOffset, mDatePaint);






            //Draw Icon and Temperatures
            if (mHighTemp != null && mLowTemp != null) {
                float tempYOffset = dateYOffset + getResources().getDimension(R.dimen.digital_date_text_margin_bottom);
                //Icon
                if (mIcon != null && !isInAmbientMode())
                    canvas.drawBitmap(mIcon, centerX - mIcon.getWidth() - mIcon.getWidth() / 4, tempYOffset - mIcon.getHeight() / 2, mDatePaint);
                //High temp
                canvas.drawText(mHighTemp, centerX, tempYOffset, mTextPaint);
                //Low temp
                float highTempSize = mTextPaint.measureText(mHighTemp);
                float highTempRightMargin = getResources().getDimension(R.dimen.digital_temp_text_margin_right);
                canvas.drawText(mLowTemp, centerX + highTempSize + highTempRightMargin, tempYOffset, mDatePaint);
            }
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

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG,"Connection failed due to" + connectionResult.getErrorMessage());
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG,"Connected ");
            Wearable.DataApi.addListener(mGoogleApiClient,Engine.this);

        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG,"Connection suspended: "+i);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG,"Got new data :)");
            for(DataEvent dataevent : dataEventBuffer)
            {
                if(dataevent.getType() == DataEvent.TYPE_CHANGED)
                {
                    DataItem dataitem = dataevent.getDataItem();
                    if(dataitem.getUri().getPath().equals(getString(R.string.API_PATH)))
                    {
                        DataMap dataMap = DataMapItem.fromDataItem(dataitem).getDataMap();
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        new FetchIcon().execute(dataMap.getAsset("icon"));
                        //https://discussions.udacity.com/t/added-some-receive-code-in-the-wear-side/190479/93
                    }
                }
            }
        }

        private class FetchIcon extends AsyncTask<Asset,Void,Void>{

            private Asset[] assets;

            @Override
            protected Void doInBackground(Asset... params) {

                Asset asset = params[0];
                mIcon = loadBitmapFromAsset(asset);

                int size = Double.valueOf(getApplicationContext().getResources().getDimension(R.dimen.icon_size)).intValue();
                mIcon = Bitmap.createScaledBitmap(mIcon, size, size, false);
                postInvalidate();

                return  null; // To satisfy void return
            }
        }

        private Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
            }
        }
    }

