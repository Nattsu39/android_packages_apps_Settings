/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settings.wifi.tether;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.settings.R;

import java.util.Collection;

/** Enforces the per-session shared data limit for Wi-Fi hotspot. */
public class WifiTetherDataLimitService extends Service {

    private static final String TAG = "WifiTetherDataLimitService";
    private static final String NOTIFICATION_CHANNEL_ID = "hotspot_data_limit";
    static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_UPDATE_MONITORING =
            "com.android.settings.wifi.tether.UPDATE_DATA_LIMIT_MONITORING";
    private static final long CHECK_INTERVAL_MS = 10_000L;

    private HandlerThread mHandlerThread;
    private Handler mWorkerHandler;
    private TetheringManager mTetheringManager;
    private WifiTetherClientRepository mClientRepository;
    private boolean mIsTetheringCallbackRegistered;
    private final Runnable mCheckLimitRunnable = this::checkLimit;
    private final TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onClientsChanged(Collection<TetheredClient> clients) {
                    mClientRepository.recordClients(clients);
                    mClientRepository.refreshUsage();
                }
            };

    public static void updateMonitoring(Context context) {
        Intent intent = new Intent(context, WifiTetherDataLimitService.class);
        intent.setAction(ACTION_UPDATE_MONITORING);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to update data limit monitoring", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mWorkerHandler = new Handler(mHandlerThread.getLooper());
        mTetheringManager = getSystemService(TetheringManager.class);
        mClientRepository = WifiTetherClientRepository.getInstance(this);
        registerTetheringCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isHotspotActive()) {
            WifiTetherSettingsStore.clearHotspotSession(this);
        }
        if (!shouldStayActive()) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (isHotspotActive()) {
            WifiTetherSettingsStore.ensureHotspotSessionStarted(this);
            if (WifiTetherSettingsStore.getSharedDataLimitBaselineBytes(this)
                    == WifiTetherSettingsStore.BASELINE_UNSET) {
                WifiTetherSettingsStore.resetSharedDataLimitBaseline(this);
            }
        }
        scheduleNextCheck(0L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mWorkerHandler != null) {
            mWorkerHandler.removeCallbacks(mCheckLimitRunnable);
        }
        unregisterTetheringCallback();
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkLimit() {
        if (!shouldStayActive()) {
            stopMonitoring();
            return;
        }

        mClientRepository.refreshUsage();
        if (!isHotspotActive()) {
            WifiTetherSettingsStore.clearHotspotSession(this);
            cancelLimitNotification();
            scheduleNextCheck(CHECK_INTERVAL_MS);
            return;
        }
        WifiTetherSettingsStore.ensureHotspotSessionStarted(this);

        long limitBytes = WifiTetherSettingsStore.getSharedDataLimitBytes(this);
        if (limitBytes <= WifiTetherSettingsStore.DATA_LIMIT_DISABLED) {
            cancelLimitNotification();
            scheduleNextCheck(CHECK_INTERVAL_MS);
            return;
        }
        long usedBytes = WifiTetherSettingsStore.getTetheringSessionBytes(this);
        if (usedBytes >= limitBytes
                && !WifiTetherSettingsStore.isSharedDataLimitAcknowledged(this)) {
            Log.i(TAG, "Shared data limit reached: used=" + usedBytes + ", limit=" + limitBytes);
            showLimitReachedNotification(usedBytes, limitBytes);
        }

        scheduleNextCheck(CHECK_INTERVAL_MS);
    }

    private boolean isHotspotActive() {
        WifiManager wifiManager = getSystemService(WifiManager.class);
        if (wifiManager == null) {
            return false;
        }
        int wifiApState = wifiManager.getWifiApState();
        return wifiApState == WifiManager.WIFI_AP_STATE_ENABLING
                || wifiApState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    private boolean shouldStayActive() {
        if (isHotspotActive()) {
            return true;
        }
        try {
            return mTetheringManager != null && mTetheringManager.getTetheredIfaces().length > 0;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read tethering state", e);
            return false;
        }
    }

    private void showLimitReachedNotification(long usedBytes, long limitBytes) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(
                new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.wifi_tether_data_limit_notification_channel),
                        NotificationManager.IMPORTANCE_HIGH));

        PendingIntent continueIntent =
                PendingIntent.getBroadcast(
                        this,
                        0,
                        new Intent(this, WifiTetherDataLimitReceiver.class)
                                .setAction(WifiTetherDataLimitReceiver.ACTION_CONTINUE_SHARING),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent =
                PendingIntent.getBroadcast(
                        this,
                        1,
                        new Intent(this, WifiTetherDataLimitReceiver.class)
                                .setAction(WifiTetherDataLimitReceiver.ACTION_STOP_SHARING),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent settingsIntent =
                PendingIntent.getActivity(
                        this,
                        2,
                        new Intent(Settings.ACTION_WIFI_TETHER_SETTING)
                                .setPackage(getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification =
                new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_wifi_tethering)
                        .setContentTitle(getString(R.string.wifi_tether_data_limit_reached_title))
                        .setContentText(
                                getString(
                                        R.string.wifi_tether_data_limit_reached_message,
                                        WifiTetherSettingsStore.formatDataLimit(this, usedBytes),
                                        WifiTetherSettingsStore.formatDataLimit(this, limitBytes)))
                        .setContentIntent(settingsIntent)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .addAction(
                                0,
                                getString(R.string.wifi_tether_data_limit_continue),
                                continueIntent)
                        .addAction(0, getString(R.string.wifi_tether_data_limit_stop), stopIntent)
                        .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void cancelLimitNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private void registerTetheringCallback() {
        if (mTetheringManager == null || mIsTetheringCallbackRegistered) {
            return;
        }
        try {
            mTetheringManager.registerTetheringEventCallback(
                    command -> mWorkerHandler.post(command), mTetheringCallback);
            mIsTetheringCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to register tethering callback", e);
        }
    }

    private void unregisterTetheringCallback() {
        if (mTetheringManager == null || !mIsTetheringCallbackRegistered) {
            return;
        }
        try {
            mTetheringManager.unregisterTetheringEventCallback(mTetheringCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to unregister tethering callback", e);
        }
        mIsTetheringCallbackRegistered = false;
    }

    private void scheduleNextCheck(long delayMillis) {
        if (mWorkerHandler == null) {
            return;
        }
        mWorkerHandler.removeCallbacks(mCheckLimitRunnable);
        mWorkerHandler.postDelayed(mCheckLimitRunnable, delayMillis);
    }

    private void stopMonitoring() {
        if (mWorkerHandler != null) {
            mWorkerHandler.removeCallbacks(mCheckLimitRunnable);
        }
        stopSelf();
    }
}
