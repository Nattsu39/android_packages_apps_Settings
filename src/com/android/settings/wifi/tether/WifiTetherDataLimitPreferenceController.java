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
import android.net.wifi.WifiManager;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;

/** Controller for the per-session shared data limit for Wi-Fi hotspot. */
public class WifiTetherDataLimitPreferenceController extends WifiTetherBasePreferenceController {

    public static final String PREF_KEY = "wifi_tether_shared_data_limit";

    private long mDataLimitBytes = WifiTetherSettingsStore.DATA_LIMIT_DISABLED;

    public WifiTetherDataLimitPreferenceController(
            Context context, OnTetherConfigUpdateListener listener) {
        super(context, listener);
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
        mDataLimitBytes = WifiTetherSettingsStore.getSharedDataLimitBytes(mContext);
        ListPreference preference = (ListPreference) mPreference;
        preference.setValue(String.valueOf(mDataLimitBytes));
        updateSummary(preference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        mDataLimitBytes = Long.parseLong((String) newValue);
        WifiTetherSettingsStore.setSharedDataLimitBytes(mContext, mDataLimitBytes);
        WifiTetherSettingsStore.setSharedDataLimitAcknowledged(mContext, false);
        WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
        boolean isHotspotActive =
                wifiManager != null
                        && (wifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLING
                                || wifiManager.getWifiApState()
                                        == WifiManager.WIFI_AP_STATE_ENABLED);
        if (isHotspotActive
                && WifiTetherSettingsStore.getSharedDataLimitBaselineBytes(mContext)
                        == WifiTetherSettingsStore.BASELINE_UNSET) {
            WifiTetherSettingsStore.resetSharedDataLimitBaseline(mContext);
        }
        updateSummary((ListPreference) preference);
        WifiTetherDataLimitService.updateMonitoring(mContext);
        return true;
    }

    private void updateSummary(ListPreference preference) {
        if (mDataLimitBytes <= WifiTetherSettingsStore.DATA_LIMIT_DISABLED) {
            preference.setSummary(R.string.wifi_tether_data_limit_unlimited);
        } else {
            preference.setSummary(
                    mContext.getString(
                            R.string.wifi_tether_data_limit_summary,
                            WifiTetherSettingsStore.formatDataLimit(mContext, mDataLimitBytes)));
        }
    }
}
