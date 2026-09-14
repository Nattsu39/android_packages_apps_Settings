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

import android.content.Context;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller for choosing the preferred Wi-Fi version used by Soft AP.
 */
public class WifiTetherWifiVersionPreferenceController extends WifiTetherBasePreferenceController
        implements WifiManager.SoftApCallback {

    public static final String PREF_KEY = "wifi_tether_wifi_version";

    private int mWifiVersion = WifiTetherSettingsStore.WIFI_VERSION_AUTO;
    private boolean mIsIeee80211axSupported = true;
    private boolean mIsIeee80211beSupported = true;

    public WifiTetherWifiVersionPreferenceController(Context context,
            OnTetherConfigUpdateListener listener) {
        super(context, listener);
        if (mWifiManager != null) {
            mWifiManager.registerSoftApCallback(context.getMainExecutor(), this);
        }
    }

    @Override
    public String getPreferenceKey() {
        return PREF_KEY;
    }

    @Override
    public void updateDisplay() {
        if (mPreference == null) {
            return;
        }
        mWifiVersion = WifiTetherSettingsStore.getWifiVersion(mContext);
        if (!isVersionSupported(mWifiVersion)) {
            mWifiVersion = WifiTetherSettingsStore.WIFI_VERSION_AUTO;
        }
        ListPreference preference = (ListPreference) mPreference;
        updateEntries(preference);
        preference.setValue(String.valueOf(mWifiVersion));
        preference.setEnabled(mIsIeee80211axSupported || mIsIeee80211beSupported);
        updateSummary(preference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        mWifiVersion = Integer.parseInt((String) newValue);
        WifiTetherSettingsStore.setWifiVersion(mContext, mWifiVersion);
        updateSummary((ListPreference) preference);
        if (mListener != null) {
            mListener.onTetherConfigUpdated(this);
        }
        return true;
    }

    @Override
    public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
        mIsIeee80211axSupported = softApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_IEEE80211_AX);
        mIsIeee80211beSupported = softApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_IEEE80211_BE);
        updateDisplay();
        mWifiManager.unregisterSoftApCallback(this);
    }

    /**
     * Applies the selected Wi-Fi version to the Soft AP configuration.
     */
    public void setupWifiVersion(SoftApConfiguration.Builder builder) {
        if (builder == null) {
            return;
        }
        switch (mWifiVersion) {
            case WifiTetherSettingsStore.WIFI_VERSION_LEGACY:
                builder.setIeee80211axEnabled(false);
                builder.setIeee80211beEnabled(false);
                break;
            case WifiTetherSettingsStore.WIFI_VERSION_WIFI6:
                builder.setIeee80211axEnabled(true);
                builder.setIeee80211beEnabled(false);
                break;
            case WifiTetherSettingsStore.WIFI_VERSION_WIFI7:
            case WifiTetherSettingsStore.WIFI_VERSION_AUTO:
            default:
                builder.setIeee80211axEnabled(true);
                builder.setIeee80211beEnabled(true);
                break;
        }
    }

    private void updateEntries(ListPreference preference) {
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        addEntry(entries, values, R.string.wifi_tether_wifi_version_auto,
                WifiTetherSettingsStore.WIFI_VERSION_AUTO);
        addEntry(entries, values, R.string.wifi_tether_wifi_version_legacy,
                WifiTetherSettingsStore.WIFI_VERSION_LEGACY);
        if (mIsIeee80211axSupported) {
            addEntry(entries, values, R.string.wifi_tether_wifi_version_wifi6,
                    WifiTetherSettingsStore.WIFI_VERSION_WIFI6);
        }
        if (mIsIeee80211beSupported) {
            addEntry(entries, values, R.string.wifi_tether_wifi_version_wifi7,
                    WifiTetherSettingsStore.WIFI_VERSION_WIFI7);
        }
        preference.setEntries(entries.toArray(new CharSequence[0]));
        preference.setEntryValues(values.toArray(new CharSequence[0]));
    }

    private void addEntry(List<CharSequence> entries, List<CharSequence> values, int titleResId,
            int value) {
        entries.add(mContext.getString(titleResId));
        values.add(Integer.toString(value));
    }

    private boolean isVersionSupported(int wifiVersion) {
        return wifiVersion == WifiTetherSettingsStore.WIFI_VERSION_AUTO
                || wifiVersion == WifiTetherSettingsStore.WIFI_VERSION_LEGACY
                || (wifiVersion == WifiTetherSettingsStore.WIFI_VERSION_WIFI6
                && mIsIeee80211axSupported)
                || (wifiVersion == WifiTetherSettingsStore.WIFI_VERSION_WIFI7
                && mIsIeee80211beSupported);
    }

    private void updateSummary(ListPreference preference) {
        if (!mIsIeee80211axSupported && !mIsIeee80211beSupported) {
            preference.setSummary(R.string.wifi_tether_feature_unavailable);
            return;
        }
        switch (mWifiVersion) {
            case WifiTetherSettingsStore.WIFI_VERSION_LEGACY:
                preference.setSummary(R.string.wifi_tether_wifi_version_legacy);
                break;
            case WifiTetherSettingsStore.WIFI_VERSION_WIFI6:
                preference.setSummary(R.string.wifi_tether_wifi_version_wifi6);
                break;
            case WifiTetherSettingsStore.WIFI_VERSION_WIFI7:
                preference.setSummary(R.string.wifi_tether_wifi_version_wifi7);
                break;
            case WifiTetherSettingsStore.WIFI_VERSION_AUTO:
            default:
                preference.setSummary(R.string.wifi_tether_wifi_version_auto);
                break;
        }
    }
}
