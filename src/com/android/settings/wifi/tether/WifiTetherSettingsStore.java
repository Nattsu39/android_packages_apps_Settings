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

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.NetworkTemplate;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;

import com.android.settings.datausage.DataUsageUtils;

/** Shared storage and helpers for Wi-Fi hotspot advanced settings. */
final class WifiTetherSettingsStore {

    private static final String TAG = "WifiTetherSettingsStore";

    static final long DATA_LIMIT_DISABLED = -1L;
    static final long BASELINE_UNSET = -1L;

    static final int WIFI_VERSION_AUTO = 0;
    static final int WIFI_VERSION_LEGACY = 1;
    static final int WIFI_VERSION_WIFI6 = 2;
    static final int WIFI_VERSION_WIFI7 = 3;

    private static final String WIFI_TETHER_SHARED_DATA_LIMIT_BYTES =
            "wifi_tether_shared_data_limit_bytes";
    private static final String WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_BYTES =
            "wifi_tether_shared_data_limit_baseline_bytes";
    private static final String WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_TIME_MS =
            "wifi_tether_shared_data_limit_baseline_time_ms";
    private static final String WIFI_TETHER_SHARED_DATA_LIMIT_ACKNOWLEDGED =
            "wifi_tether_shared_data_limit_acknowledged";
    private static final String WIFI_TETHER_HOTSPOT_SESSION_START_TIME_MS =
            "wifi_tether_hotspot_session_start_time_ms";
    private static final String WIFI_TETHER_WIFI_VERSION = "wifi_tether_wifi_version";
    private static final String WIFI_TETHER_5G_160MHZ_ENABLED =
            "wifi_tether_5g_160mhz_enabled";

    private WifiTetherSettingsStore() {}

    static long getSharedDataLimitBytes(Context context) {
        return Settings.Global.getLong(
                context.getContentResolver(),
                WIFI_TETHER_SHARED_DATA_LIMIT_BYTES,
                DATA_LIMIT_DISABLED);
    }

    static void setSharedDataLimitBytes(Context context, long limitBytes) {
        Settings.Global.putLong(
                context.getContentResolver(), WIFI_TETHER_SHARED_DATA_LIMIT_BYTES, limitBytes);
    }

    static long getSharedDataLimitBaselineBytes(Context context) {
        return Settings.Global.getLong(
                context.getContentResolver(),
                WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_BYTES,
                BASELINE_UNSET);
    }

    static long getHotspotSessionStartTimeMillis(Context context) {
        return Settings.Global.getLong(
                context.getContentResolver(),
                WIFI_TETHER_HOTSPOT_SESSION_START_TIME_MS,
                BASELINE_UNSET);
    }

    static long ensureHotspotSessionStarted(Context context) {
        long startTime = getHotspotSessionStartTimeMillis(context);
        if (startTime != BASELINE_UNSET) {
            return startTime;
        }
        return startHotspotSession(context);
    }

    static long startHotspotSession(Context context) {
        long startTime = System.currentTimeMillis();
        Settings.Global.putLong(
                context.getContentResolver(),
                WIFI_TETHER_HOTSPOT_SESSION_START_TIME_MS,
                startTime);
        return startTime;
    }

    static void clearHotspotSession(Context context) {
        Settings.Global.putLong(
                context.getContentResolver(),
                WIFI_TETHER_HOTSPOT_SESSION_START_TIME_MS,
                BASELINE_UNSET);
    }

    static void resetSharedDataLimitBaseline(Context context) {
        ContentResolver resolver = context.getContentResolver();
        ensureHotspotSessionStarted(context);
        Settings.Global.putLong(
                resolver,
                WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_BYTES,
                getTetheringTotalBytes(context));
        Settings.Global.putLong(
                resolver,
                WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_TIME_MS,
                System.currentTimeMillis());
        Settings.Global.putInt(resolver, WIFI_TETHER_SHARED_DATA_LIMIT_ACKNOWLEDGED, 0);
    }

    static void clearSharedDataLimitBaseline(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Settings.Global.putLong(
                resolver, WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_BYTES, BASELINE_UNSET);
        Settings.Global.putLong(
                resolver, WIFI_TETHER_SHARED_DATA_LIMIT_BASELINE_TIME_MS, BASELINE_UNSET);
        Settings.Global.putInt(resolver, WIFI_TETHER_SHARED_DATA_LIMIT_ACKNOWLEDGED, 0);
    }

    static boolean isSharedDataLimitAcknowledged(Context context) {
        return Settings.Global.getInt(
                        context.getContentResolver(), WIFI_TETHER_SHARED_DATA_LIMIT_ACKNOWLEDGED, 0)
                != 0;
    }

    static void setSharedDataLimitAcknowledged(Context context, boolean acknowledged) {
        Settings.Global.putInt(
                context.getContentResolver(),
                WIFI_TETHER_SHARED_DATA_LIMIT_ACKNOWLEDGED,
                acknowledged ? 1 : 0);
    }

    static int getWifiVersion(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(), WIFI_TETHER_WIFI_VERSION, WIFI_VERSION_AUTO);
    }

    static void setWifiVersion(Context context, int wifiVersion) {
        Settings.Global.putInt(context.getContentResolver(), WIFI_TETHER_WIFI_VERSION, wifiVersion);
    }

    static boolean is5g160MhzEnabled(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(), WIFI_TETHER_5G_160MHZ_ENABLED, 0) != 0;
    }

    static void set5g160MhzEnabled(Context context, boolean enabled) {
        Settings.Global.putInt(
                context.getContentResolver(), WIFI_TETHER_5G_160MHZ_ENABLED, enabled ? 1 : 0);
    }

    static String formatDataLimit(Context context, long bytes) {
        if (bytes <= DATA_LIMIT_DISABLED) {
            return context.getString(
                    com.android.settings.R.string.wifi_tether_data_limit_unlimited);
        }
        return Formatter.formatFileSize(context, bytes);
    }

    static long getTetheringSessionBytes(Context context) {
        long sessionStart = getHotspotSessionStartTimeMillis(context);
        if (sessionStart == BASELINE_UNSET) {
            return 0L;
        }
        return getTetheringUsage(context, sessionStart, System.currentTimeMillis()).getTotalBytes();
    }

    static long getTetheringTotalBytes(Context context) {
        return getTetheringUsage(
                context, 0L, System.currentTimeMillis()).getTotalBytes();
    }

    static WifiTetherClientRepository.UsageBytes getTetheringUsage(
            Context context, long startTime, long endTime) {
        WifiTetherClientRepository.UsageBytes totalUsage =
                new WifiTetherClientRepository.UsageBytes();
        if (startTime >= endTime) {
            return totalUsage;
        }
        if (DataUsageUtils.hasMobileData(context)) {
            totalUsage.add(
                    queryTetheringUsage(
                            context, buildUpstreamTemplate(NetworkTemplate.MATCH_MOBILE),
                            startTime, endTime));
        }
        if (DataUsageUtils.hasWifiRadio(context)) {
            totalUsage.add(
                    queryTetheringUsage(
                            context, buildUpstreamTemplate(NetworkTemplate.MATCH_WIFI),
                            startTime, endTime));
        }
        totalUsage.add(
                queryTetheringUsage(
                        context, buildUpstreamTemplate(NetworkTemplate.MATCH_BLUETOOTH),
                        startTime, endTime));
        totalUsage.add(
                queryTetheringUsage(
                        context, buildUpstreamTemplate(NetworkTemplate.MATCH_ETHERNET),
                        startTime, endTime));
        return totalUsage;
    }

    private static NetworkTemplate buildUpstreamTemplate(int matchRule) {
        return new NetworkTemplate.Builder(matchRule).build();
    }

    private static WifiTetherClientRepository.UsageBytes queryTetheringUsage(
            Context context, NetworkTemplate template, long startTime, long endTime) {
        NetworkStatsManager statsManager = context.getSystemService(NetworkStatsManager.class);
        if (statsManager == null) {
            return new WifiTetherClientRepository.UsageBytes();
        }
        return queryTetheringUsage(statsManager, template, startTime, endTime);
    }

    private static WifiTetherClientRepository.UsageBytes queryTetheringUsage(
            NetworkStatsManager statsManager, NetworkTemplate template, long startTime,
            long endTime) {
        NetworkStats stats = null;
        try {
            WifiTetherClientRepository.UsageBytes totalUsage =
                    new WifiTetherClientRepository.UsageBytes();
            WifiTetherClientRepository.UsageBytes taggedUsage =
                    new WifiTetherClientRepository.UsageBytes();
            stats = statsManager.querySummary(template, startTime, endTime);
            totalUsage.add(sumTetheringBuckets(stats));
            stats.close();
            stats = statsManager.queryTaggedSummary(template, startTime, endTime);
            taggedUsage.add(sumTetheringBuckets(stats));
            return maxByDirection(totalUsage, taggedUsage);
        } catch (Exception e) {
            Log.e(TAG, "Exception querying tethering data usage", e);
        } finally {
            if (stats != null) {
                stats.close();
            }
        }
        return new WifiTetherClientRepository.UsageBytes();
    }

    private static WifiTetherClientRepository.UsageBytes maxByDirection(
            WifiTetherClientRepository.UsageBytes totalUsage,
            WifiTetherClientRepository.UsageBytes taggedUsage) {
        return new WifiTetherClientRepository.UsageBytes(
                Math.max(totalUsage.getRxBytes(), taggedUsage.getRxBytes()),
                Math.max(totalUsage.getTxBytes(), taggedUsage.getTxBytes()));
    }

    private static WifiTetherClientRepository.UsageBytes sumTetheringBuckets(NetworkStats stats) {
        WifiTetherClientRepository.UsageBytes usage =
                new WifiTetherClientRepository.UsageBytes();
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket);
            if (bucket.getUid() != NetworkStats.Bucket.UID_TETHERING) {
                continue;
            }
            usage.add(bucket.getRxBytes(), bucket.getTxBytes());
        }
        return usage;
    }
}
