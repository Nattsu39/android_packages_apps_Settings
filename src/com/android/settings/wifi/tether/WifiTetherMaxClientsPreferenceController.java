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
import androidx.annotation.VisibleForTesting;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the maximum number of Wi-Fi hotspot clients.
 */
public class WifiTetherMaxClientsPreferenceController extends WifiTetherBasePreferenceController
        implements WifiManager.SoftApCallback {

    public static final String PREF_KEY = "wifi_tether_max_clients";

    @VisibleForTesting
    static final int DEFAULT_MAX_CLIENTS = 10;

    private int mMaxClients;
    private int mMaxSupportedClients = DEFAULT_MAX_CLIENTS;
    private boolean mIsClientForceDisconnectSupported = true;

    public WifiTetherMaxClientsPreferenceController(Context context,
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
        if (mPreference == null || mWifiManager == null) {
            return;
        }

        final SoftApConfiguration config = mWifiManager.getSoftApConfiguration();
        mMaxClients = (config != null) ? config.getMaxNumberOfClients() : 0;

        final ListPreference preference = (ListPreference) mPreference;
        updateEntries(preference);
        preference.setValue(String.valueOf(mMaxClients));
        updateSummary(preference);
        preference.setEnabled(mIsClientForceDisconnectSupported);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        mMaxClients = Integer.parseInt((String) newValue);
        updateSummary((ListPreference) preference);
        if (mListener != null) {
            mListener.onTetherConfigUpdated(this);
        }
        return true;
    }

    @Override
    public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
        mIsClientForceDisconnectSupported = softApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT);
        final int maxSupportedClients = softApCapability.getMaxSupportedClients();
        if (maxSupportedClients > 0) {
            mMaxSupportedClients = maxSupportedClients;
        }
        updateDisplay();
        mWifiManager.unregisterSoftApCallback(this);
    }

    /**
     * Applies the selected maximum client count to the Soft AP configuration.
     */
    public void setupMaxNumberOfClients(SoftApConfiguration.Builder builder) {
        if (builder == null) {
            return;
        }
        builder.setMaxNumberOfClients(mIsClientForceDisconnectSupported ? mMaxClients : 0);
    }

    private void updateEntries(ListPreference preference) {
        final int maxClientCount = Math.max(mMaxSupportedClients, mMaxClients);
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        entries.add(mContext.getString(R.string.wifi_tether_max_clients_auto));
        values.add("0");
        for (int i = 1; i <= maxClientCount; i++) {
            entries.add(Integer.toString(i));
            values.add(Integer.toString(i));
        }
        preference.setEntries(entries.toArray(new CharSequence[0]));
        preference.setEntryValues(values.toArray(new CharSequence[0]));
    }

    private void updateSummary(ListPreference preference) {
        if (!mIsClientForceDisconnectSupported) {
            preference.setSummary(R.string.wifi_tether_feature_unavailable);
        } else if (mMaxClients == 0) {
            preference.setSummary(R.string.wifi_tether_max_clients_auto);
        } else {
            preference.setSummary(mContext.getString(
                    R.string.wifi_tether_max_clients_summary, mMaxClients));
        }
    }
}
