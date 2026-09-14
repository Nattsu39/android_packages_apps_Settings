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
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Controller for selecting a fixed Wi-Fi hotspot channel.
 */
public class WifiTetherChannelPreferenceController extends WifiTetherBasePreferenceController
        implements WifiManager.SoftApCallback {

    private static final String TAG = "WifiTetherChannelPref";
    public static final String PREF_KEY = "wifi_tether_channel";

    @VisibleForTesting
    static final String AUTO_VALUE = "0:0";

    private final List<ChannelOption> mChannelOptions = new ArrayList<>();
    private SoftApCapability mSoftApCapability;
    private String mChannelValue = AUTO_VALUE;
    private boolean mCurrentConfigHasMultipleFixedChannels;
    private boolean mRequire5g160Mhz;

    public WifiTetherChannelPreferenceController(Context context,
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
        rebuildChannelOptions(config);
        mChannelValue = getChannelValue(config);

        if (mRequire5g160Mhz && !isCurrentChannel160MhzCapable()) {
            mChannelValue = AUTO_VALUE;
        }
        ensureCurrentChannelOption();
        if (mPreference instanceof ListPreference) {
            final ListPreference preference = (ListPreference) mPreference;
            preference.setEntries(getEntries());
            preference.setEntryValues(getEntryValues());
            preference.setValue(mChannelValue);
            preference.setEnabled(mChannelOptions.size() > 1);
        } else {
            mPreference.setEnabled(true);
        }
        updateSummary(mPreference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (!(preference instanceof ListPreference)) {
            return false;
        }
        mChannelValue = (String) newValue;
        updateSummary(preference);
        if (mListener != null) {
            mListener.onTetherConfigUpdated(this);
        }
        return true;
    }

    @Override
    public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
        mSoftApCapability = softApCapability;
        updateDisplay();
        mWifiManager.unregisterSoftApCallback(this);
    }

    public void setRequire5g160Mhz(boolean require5g160Mhz) {
        mRequire5g160Mhz = require5g160Mhz;
        if (mRequire5g160Mhz && !isCurrentChannel160MhzCapable()) {
            mChannelValue = AUTO_VALUE;
        }
    }

    /**
     * Clears a fixed channel selection while preserving the current band selection.
     */
    public void resetChannelIfAutomatic(SoftApConfiguration.Builder builder,
            SoftApConfiguration currentConfig) {
        if (builder == null || currentConfig == null || !AUTO_VALUE.equals(mChannelValue)
                || mRequire5g160Mhz) {
            return;
        }
        SparseIntArray channels = currentConfig.getChannels();
        SparseIntArray autoChannels = new SparseIntArray(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            autoChannels.put(channels.keyAt(i), 0);
        }
        if (autoChannels.size() > 0) {
            builder.setChannels(autoChannels);
        }
    }

    /**
     * Applies the selected fixed channel to the Soft AP configuration.
     */
    @SuppressLint("NewApi")
    public void setupFixedChannel(SoftApConfiguration.Builder builder) {
        if (builder == null) {
            return;
        }
        if (mRequire5g160Mhz) {
            if (AUTO_VALUE.equals(mChannelValue)) {
                set5gAutoChannel(builder);
                return;
            }
            ChannelOption selectedOption = findOption(mChannelValue);
            if (selectedOption != null && selectedOption.supports160Mhz) {
                builder.setChannel(selectedOption.channel, selectedOption.band);
                return;
            }
            set5gAutoChannel(builder);
            return;
        }
        if (AUTO_VALUE.equals(mChannelValue)) {
            return;
        }
        ChannelOption option = ChannelOption.fromValue(mChannelValue);
        if (option == null) {
            return;
        }
        builder.setChannel(option.channel, option.band);
    }

    private void rebuildChannelOptions(SoftApConfiguration config) {
        mChannelOptions.clear();
        mCurrentConfigHasMultipleFixedChannels = false;
        Set<String> values = new HashSet<>();
        addChannelOption(new ChannelOption(0, 0, mContext.getString(
                R.string.wifi_tether_channel_auto), false), values);
        addAllowedChannelOptions(WifiScanner.WIFI_BAND_24_GHZ,
                SoftApConfiguration.BAND_2GHZ, getBandLabel(SoftApConfiguration.BAND_2GHZ),
                values);
        addAllowedChannelOptions(WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS,
                SoftApConfiguration.BAND_5GHZ, getBandLabel(SoftApConfiguration.BAND_5GHZ),
                values);
        addAllowedChannelOptions(WifiScanner.WIFI_BAND_6_GHZ,
                SoftApConfiguration.BAND_6GHZ, getBandLabel(SoftApConfiguration.BAND_6GHZ),
                values);
        addAllowedChannelOptions(WifiScanner.WIFI_BAND_60_GHZ,
                SoftApConfiguration.BAND_60GHZ, getBandLabel(SoftApConfiguration.BAND_60GHZ),
                values);

        if (mChannelOptions.size() == 1 && mSoftApCapability != null) {
            addCapabilityChannelOptions(SoftApConfiguration.BAND_2GHZ, values);
            addCapabilityChannelOptions(SoftApConfiguration.BAND_5GHZ, values);
            addCapabilityChannelOptions(SoftApConfiguration.BAND_6GHZ, values);
            addCapabilityChannelOptions(SoftApConfiguration.BAND_60GHZ, values);
        }

        if (config != null) {
            SparseIntArray channels = config.getChannels();
            mCurrentConfigHasMultipleFixedChannels = channels.size() > 1 && hasFixedChannel(
                    channels);
        }
    }

    private void addAllowedChannelOptions(int wifiScannerBand, int softApBand, String bandLabel,
            Set<String> values) {
        try {
            List<WifiAvailableChannel> channels =
                    mWifiManager.getAllowedChannels(wifiScannerBand, OP_MODE_SAP);
            if (channels == null) {
                return;
            }
            for (WifiAvailableChannel channel : channels) {
                int channelNumber = ScanResult.convertFrequencyMhzToChannelIfSupported(
                        channel.getFrequencyMhz());
                if (channelNumber != ScanResult.UNSPECIFIED) {
                    boolean supports160Mhz = softApBand == SoftApConfiguration.BAND_5GHZ
                            && WifiTether160MhzPreferenceController.is160MhzChannelWidth(
                                    channel.getChannelWidth());
                    addChannelOption(new ChannelOption(softApBand, channelNumber,
                            getChannelLabel(bandLabel, channelNumber, supports160Mhz),
                            supports160Mhz), values);
                }
            }
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException e) {
            Log.d(TAG, "Allowed channel query unavailable for band " + wifiScannerBand, e);
        }
    }

    private void addCapabilityChannelOptions(int band, Set<String> values) {
        int[] channels = mSoftApCapability.getSupportedChannelList(band);
        String bandLabel = getBandLabel(band);
        for (int channel : channels) {
            addChannelOption(new ChannelOption(band, channel,
                            getChannelLabel(bandLabel, channel, false), false), values);
        }
    }

    private void ensureCurrentChannelOption() {
        if (AUTO_VALUE.equals(mChannelValue) || containsValue(mChannelValue)) {
            return;
        }
        ChannelOption option = ChannelOption.fromValue(mChannelValue);
        if (option != null) {
            mChannelOptions.add(new ChannelOption(option.band, option.channel,
                    getChannelLabel(getBandLabel(option.band), option.channel, false), false));
        }
    }

    private void addChannelOption(ChannelOption option, Set<String> values) {
        if (values.add(option.value)) {
            mChannelOptions.add(option);
        }
    }

    private void set5gAutoChannel(SoftApConfiguration.Builder builder) {
        SparseIntArray channels = new SparseIntArray(1);
        channels.put(SoftApConfiguration.BAND_5GHZ, 0);
        builder.setChannels(channels);
    }

    private boolean containsValue(String value) {
        for (ChannelOption option : mChannelOptions) {
            if (option.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private CharSequence[] getEntries() {
        CharSequence[] entries = new CharSequence[mChannelOptions.size()];
        for (int i = 0; i < mChannelOptions.size(); i++) {
            entries[i] = mChannelOptions.get(i).label;
        }
        return entries;
    }

    private CharSequence[] getEntryValues() {
        CharSequence[] values = new CharSequence[mChannelOptions.size()];
        for (int i = 0; i < mChannelOptions.size(); i++) {
            values[i] = mChannelOptions.get(i).value;
        }
        return values;
    }

    private String getChannelValue(SoftApConfiguration config) {
        if (config == null) {
            return AUTO_VALUE;
        }
        SparseIntArray channels = config.getChannels();
        if (channels.size() == 1 && channels.valueAt(0) > 0) {
            return ChannelOption.encode(channels.keyAt(0), channels.valueAt(0));
        }
        return AUTO_VALUE;
    }

    private boolean hasFixedChannel(SparseIntArray channels) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.valueAt(i) > 0) {
                return true;
            }
        }
        return false;
    }

    private void updateSummary(Preference preference) {
        if (mRequire5g160Mhz && !WifiTether160MhzPreferenceController.has5g160MhzChannels(
                mWifiManager)) {
            preference.setSummary(R.string.wifi_tether_5g_160mhz_unavailable);
            return;
        }
        if (mCurrentConfigHasMultipleFixedChannels && AUTO_VALUE.equals(mChannelValue)) {
            preference.setSummary(R.string.wifi_tether_channel_multiple_summary);
            return;
        }
        if (mChannelOptions.size() <= 1) {
            preference.setSummary(R.string.wifi_tether_channel_unavailable);
            return;
        }
        if (mRequire5g160Mhz && AUTO_VALUE.equals(mChannelValue)) {
            preference.setSummary(R.string.wifi_tether_channel_160mhz_auto_summary);
            return;
        }
        ChannelOption option = findOption(mChannelValue);
        preference.setSummary(option != null ? option.label
                : mContext.getString(R.string.wifi_tether_channel_auto));
    }

    private ChannelOption findOption(String value) {
        for (ChannelOption option : mChannelOptions) {
            if (option.value.equals(value)) {
                return option;
            }
        }
        return null;
    }

    private boolean isCurrentChannel160MhzCapable() {
        if (AUTO_VALUE.equals(mChannelValue)) {
            return true;
        }
        ChannelOption option = findOption(mChannelValue);
        return option != null && option.supports160Mhz;
    }

    private String getChannelLabel(String bandLabel, int channel, boolean supports160Mhz) {
        String label = mContext.getString(R.string.wifi_tether_channel_summary, bandLabel, channel);
        return supports160Mhz
                ? mContext.getString(R.string.wifi_tether_channel_160mhz_summary, label)
                : label;
    }

    private String getBandLabel(int band) {
        switch (band) {
            case SoftApConfiguration.BAND_2GHZ:
                return "2.4 GHz";
            case SoftApConfiguration.BAND_5GHZ:
                return "5 GHz";
            case SoftApConfiguration.BAND_6GHZ:
                return "6 GHz";
            case SoftApConfiguration.BAND_60GHZ:
                return "60 GHz";
            default:
                return "";
        }
    }

    private static final class ChannelOption {
        final int band;
        final int channel;
        final String value;
        final String label;
        final boolean supports160Mhz;

        ChannelOption(int band, int channel, String label, boolean supports160Mhz) {
            this.band = band;
            this.channel = channel;
            this.value = encode(band, channel);
            this.label = label;
            this.supports160Mhz = supports160Mhz;
        }

        static String encode(int band, int channel) {
            return band + ":" + channel;
        }

        static ChannelOption fromValue(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split(":");
            if (parts.length != 2) {
                return null;
            }
            try {
                return new ChannelOption(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        value, false);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
