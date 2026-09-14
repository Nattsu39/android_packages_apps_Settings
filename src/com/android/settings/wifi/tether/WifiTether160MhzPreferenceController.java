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

import static android.net.wifi.WifiAvailableChannel.OP_MODE_SAP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;

import java.util.List;

/** Controller for enabling 5 GHz 160 MHz hotspot operation. */
public class WifiTether160MhzPreferenceController extends WifiTetherBasePreferenceController {

    private static final String TAG = "WifiTether160MhzPref";
    public static final String PREF_KEY = "wifi_tether_5g_160mhz";

    private boolean mEnabled;

    public WifiTether160MhzPreferenceController(Context context,
            OnTetherConfigUpdateListener listener) {
        super(context, listener);
    }

    @Override
    public String getPreferenceKey() {
        return PREF_KEY;
    }

    @Override
    public void updateDisplay() {
        if (!(mPreference instanceof SwitchPreferenceCompat)) {
            return;
        }
        SwitchPreferenceCompat preference = (SwitchPreferenceCompat) mPreference;
        SoftApConfiguration config = mWifiManager != null
                ? mWifiManager.getSoftApConfiguration() : null;
        if (config != null) {
            // The SoftApConfiguration is the source of truth. The global setting is kept only
            // for compatibility with configurations saved by older Settings builds.
            mEnabled = is5g160MhzEnabled(config);
            if (WifiTetherSettingsStore.is5g160MhzEnabled(mContext) != mEnabled) {
                WifiTetherSettingsStore.set5g160MhzEnabled(mContext, mEnabled);
            }
        } else {
            mEnabled = WifiTetherSettingsStore.is5g160MhzEnabled(mContext);
        }
        boolean supported = has5g160MhzChannels(mWifiManager);
        if (mEnabled && !supported) {
            mEnabled = false;
            WifiTetherSettingsStore.set5g160MhzEnabled(mContext, false);
        }
        preference.setChecked(mEnabled);
        preference.setEnabled(supported);
        preference.setSummary(
                supported
                        ? R.string.wifi_tether_5g_160mhz_summary
                        : R.string.wifi_tether_5g_160mhz_unavailable);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean enabled = (Boolean) newValue;
        if (enabled && !has5g160MhzChannels(mWifiManager)) {
            updateDisplay();
            return false;
        }
        mEnabled = enabled;
        WifiTetherSettingsStore.set5g160MhzEnabled(mContext, enabled);
        if (mListener != null) {
            mListener.onTetherConfigUpdated(this);
        }
        return true;
    }

    public boolean is5g160MhzEnabled() {
        return mEnabled;
    }

    @SuppressLint("NewApi")
    static boolean is5g160MhzEnabled(SoftApConfiguration config) {
        return config != null
                && config.getMaxChannelBandwidth() == SoftApInfo.CHANNEL_WIDTH_160MHZ;
    }

    @SuppressLint("NewApi")
    public void setup5g160MhzMode(SoftApConfiguration.Builder builder) {
        if (builder == null) {
            return;
        }
        builder.setMaxChannelBandwidth(
                mEnabled ? SoftApInfo.CHANNEL_WIDTH_160MHZ : SoftApInfo.CHANNEL_WIDTH_AUTO);
    }

    static boolean has5g160MhzChannels(WifiManager wifiManager) {
        if (wifiManager == null) {
            return false;
        }
        try {
            List<WifiAvailableChannel> channels =
                    wifiManager.getAllowedChannels(WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS,
                            OP_MODE_SAP);
            if (channels == null) {
                return false;
            }
            for (WifiAvailableChannel channel : channels) {
                if (is160MhzChannelWidth(channel.getChannelWidth())) {
                    return true;
                }
            }
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException e) {
            Log.d(TAG, "Unable to query 5 GHz 160 MHz hotspot channels", e);
        }
        return false;
    }

    static boolean is160MhzChannelWidth(int channelWidth) {
        // 80+80 MHz is a different, non-contiguous mode and cannot satisfy a 160 MHz request.
        return channelWidth == ScanResult.CHANNEL_WIDTH_160MHZ;
    }
}
