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

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.TetheringManager;
import android.net.wifi.WifiManager;

/** Updates Wi-Fi hotspot shared data limit monitoring. */
public class WifiTetherDataLimitReceiver extends BroadcastReceiver {

    static final String ACTION_CONTINUE_SHARING =
            "com.android.settings.wifi.tether.CONTINUE_SHARING";
    static final String ACTION_STOP_SHARING = "com.android.settings.wifi.tether.STOP_SHARING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_CONTINUE_SHARING.equals(action)) {
            WifiTetherSettingsStore.setSharedDataLimitAcknowledged(context, true);
            cancelLimitNotification(context);
        } else if (ACTION_STOP_SHARING.equals(action)) {
            WifiTetherSettingsStore.setSharedDataLimitAcknowledged(context, true);
            TetheringManager tetheringManager = context.getSystemService(TetheringManager.class);
            if (tetheringManager != null) {
                tetheringManager.stopTethering(TetheringManager.TETHERING_WIFI);
            }
            cancelLimitNotification(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (!isHotspotActive(context)) {
                WifiTetherSettingsStore.clearHotspotSession(context);
                WifiTetherSettingsStore.clearSharedDataLimitBaseline(context);
                cancelLimitNotification(context);
            }
        } else if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
            int state =
                    intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_AP_STATE, WifiManager.WIFI_AP_STATE_DISABLED);
            if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
                WifiTetherSettingsStore.startHotspotSession(context);
                WifiTetherSettingsStore.resetSharedDataLimitBaseline(context);
            } else if (state == WifiManager.WIFI_AP_STATE_DISABLED
                    || state == WifiManager.WIFI_AP_STATE_FAILED) {
                WifiTetherSettingsStore.clearHotspotSession(context);
                WifiTetherSettingsStore.clearSharedDataLimitBaseline(context);
                cancelLimitNotification(context);
            }
        }
        WifiTetherDataLimitService.updateMonitoring(context);
    }

    private static boolean isHotspotActive(Context context) {
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager == null) {
            return false;
        }
        int wifiApState = wifiManager.getWifiApState();
        return wifiApState == WifiManager.WIFI_AP_STATE_ENABLING
                || wifiApState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    private static void cancelLimitNotification(Context context) {
        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(WifiTetherDataLimitService.NOTIFICATION_ID);
        }
    }
}
