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
import android.app.settings.SettingsEnums;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.wifi.repository.WifiHotspotRepository;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wi-Fi hotspot channel selection grouped by band.
 */
public class WifiTetherChannelSettings extends DashboardFragment implements
        SelectorWithWidgetPreference.OnClickListener {

    private static final String TAG = "WifiTetherChannelSettings";

    private static final BandOption[] BAND_OPTIONS = new BandOption[] {
            new BandOption(WifiScanner.WIFI_BAND_24_GHZ, SoftApConfiguration.BAND_2GHZ),
            new BandOption(WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS, SoftApConfiguration.BAND_5GHZ),
            new BandOption(WifiScanner.WIFI_BAND_6_GHZ, SoftApConfiguration.BAND_6GHZ),
            new BandOption(WifiScanner.WIFI_BAND_60_GHZ, SoftApConfiguration.BAND_60GHZ),
    };

    private WifiManager mWifiManager;
    private WifiHotspotRepository mWifiHotspotRepository;
    private SoftApCapability mSoftApCapability;
    private String mSelectedValue = WifiTetherChannelPreferenceController.AUTO_VALUE;
    private boolean mIsCallbackRegistered;
    private boolean mIs5g160MhzEnabled;

    private final WifiManager.SoftApCallback mSoftApCallback = new WifiManager.SoftApCallback() {
        @Override
        public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
            mSoftApCapability = softApCapability;
            refreshPreferences();
        }
    };

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.wifi_tether_channel_settings;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.WIFI_TETHER_SETTINGS;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mWifiManager = getContext().getSystemService(WifiManager.class);
        mWifiHotspotRepository = FeatureFactory.getFeatureFactory().getWifiFeatureProvider()
                .getWifiHotspotRepository();
        refreshPreferences();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mWifiManager != null && !mIsCallbackRegistered) {
            try {
                mWifiManager.registerSoftApCallback(getContext().getMainExecutor(),
                        mSoftApCallback);
                mIsCallbackRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to register Soft AP callback", e);
            }
        }
        refreshPreferences();
    }

    @Override
    public void onStop() {
        if (mWifiManager != null && mIsCallbackRegistered) {
            mWifiManager.unregisterSoftApCallback(mSoftApCallback);
            mIsCallbackRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onRadioButtonClicked(SelectorWithWidgetPreference preference) {
        ChannelOption option = ChannelOption.fromValue(preference.getKey());
        if (option == null || option.value.equals(mSelectedValue)) {
            return;
        }
        ChannelOption displayedOption = findOption(option.value);
        if (mIs5g160MhzEnabled
                && displayedOption != null
                && !displayedOption.supports160Mhz
                && !WifiTetherChannelPreferenceController.AUTO_VALUE.equals(option.value)) {
            showDisable160MhzDialog(displayedOption);
            return;
        }
        applyChannelOption(displayedOption != null ? displayedOption : option,
                mIs5g160MhzEnabled);
    }

    private void refreshPreferences() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }

        screen.removeAll();
        SoftApConfiguration config = mWifiManager != null ? mWifiManager.getSoftApConfiguration()
                : null;
        if (config != null) {
            mIs5g160MhzEnabled = WifiTether160MhzPreferenceController
                    .is5g160MhzEnabled(config);
            if (WifiTetherSettingsStore.is5g160MhzEnabled(requireContext())
                    != mIs5g160MhzEnabled) {
                WifiTetherSettingsStore.set5g160MhzEnabled(requireContext(),
                        mIs5g160MhzEnabled);
            }
        } else {
            mIs5g160MhzEnabled = WifiTetherSettingsStore.is5g160MhzEnabled(requireContext());
        }
        mSelectedValue = getChannelValue(config);

        screen.addPreference(buildChannelPreference(new ChannelOption(0, 0,
                getString(R.string.wifi_tether_channel_auto), true)));

        boolean hasFixedChannel = false;
        if (mWifiManager == null || mWifiHotspotRepository == null) {
            addUnavailablePreference(screen);
            return;
        }
        Set<String> values = new HashSet<>();
        for (BandOption bandOption : BAND_OPTIONS) {
            if (bandOption.softApBand == SoftApConfiguration.BAND_6GHZ
                    && !mWifiHotspotRepository.isWpa3SaeSupported()) {
                continue;
            }
            List<ChannelOption> channelOptions = getChannelOptions(bandOption, values);
            if (channelOptions.isEmpty()) {
                continue;
            }
            hasFixedChannel = true;
            PreferenceCategory category = new PreferenceCategory(getPrefContext());
            category.setTitle(getBandLabel(bandOption.softApBand));
            screen.addPreference(category);
            for (ChannelOption option : channelOptions) {
                category.addPreference(buildChannelPreference(option));
            }
        }

        if (!hasFixedChannel) {
            addUnavailablePreference(screen);
        }
    }

    private void addUnavailablePreference(PreferenceScreen screen) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(R.string.wifi_tether_channel_unavailable);
        preference.setSelectable(false);
        screen.addPreference(preference);
    }

    private SelectorWithWidgetPreference buildChannelPreference(ChannelOption option) {
        SelectorWithWidgetPreference preference =
                new SelectorWithWidgetPreference(getPrefContext());
        preference.setPersistent(false);
        preference.setKey(option.value);
        preference.setTitle(option.label);
        preference.setSummary(getChannelSummary(option));
        preference.setOnClickListener(this);
        preference.setChecked(option.value.equals(mSelectedValue));
        return preference;
    }

    private CharSequence getChannelSummary(ChannelOption option) {
        if (!mIs5g160MhzEnabled) {
            return null;
        }
        if (WifiTetherChannelPreferenceController.AUTO_VALUE.equals(option.value)) {
            return getText(R.string.wifi_tether_channel_160mhz_auto_summary);
        }
        return getText(
                option.supports160Mhz
                        ? R.string.wifi_tether_channel_160mhz_supported_summary
                        : R.string.wifi_tether_channel_160mhz_disable_hint);
    }

    private List<ChannelOption> getChannelOptions(BandOption bandOption, Set<String> values) {
        List<ChannelOption> options = new ArrayList<>();
        String bandLabel = getBandLabel(bandOption.softApBand);
        try {
            List<WifiAvailableChannel> channels =
                    mWifiManager.getAllowedChannels(bandOption.scannerBand, OP_MODE_SAP);
            if (channels != null) {
                for (WifiAvailableChannel channel : channels) {
                    int channelNumber = ScanResult.convertFrequencyMhzToChannelIfSupported(
                            channel.getFrequencyMhz());
                    if (channelNumber != ScanResult.UNSPECIFIED) {
                        boolean supports160Mhz =
                                bandOption.softApBand == SoftApConfiguration.BAND_5GHZ
                                        && WifiTether160MhzPreferenceController
                                                .is160MhzChannelWidth(channel.getChannelWidth());
                        addChannelOption(options, values, bandOption.softApBand, channelNumber,
                                getChannelLabel(bandLabel, channelNumber, supports160Mhz),
                                supports160Mhz);
                    }
                }
            }
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException e) {
            Log.d(TAG, "Allowed channel query unavailable for band " + bandOption.scannerBand, e);
        }

        if (options.isEmpty() && mSoftApCapability != null) {
            int[] channels = mSoftApCapability.getSupportedChannelList(bandOption.softApBand);
            for (int channel : channels) {
                addChannelOption(options, values, bandOption.softApBand, channel,
                        getChannelLabel(bandLabel, channel, false), false);
            }
        }
        options.sort((first, second) -> Integer.compare(first.channel, second.channel));
        return options;
    }

    private void addChannelOption(List<ChannelOption> options, Set<String> values, int band,
            int channel, String label, boolean supports160Mhz) {
        ChannelOption option = new ChannelOption(band, channel, label, supports160Mhz);
        if (values.add(option.value)) {
            options.add(option);
        }
    }

    private void showDisable160MhzDialog(ChannelOption option) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wifi_tether_5g_160mhz_disable_dialog_title)
                .setMessage(R.string.wifi_tether_5g_160mhz_disable_dialog_message)
                .setPositiveButton(
                        R.string.wifi_tether_5g_160mhz_disable_dialog_positive,
                        (dialog, which) -> {
                            WifiTetherSettingsStore.set5g160MhzEnabled(requireContext(), false);
                            mIs5g160MhzEnabled = false;
                            applyChannelOption(option, false);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("NewApi")
    private void applyChannelOption(ChannelOption option, boolean keep160MhzEnabled) {
        if (mWifiManager == null) {
            return;
        }
        SoftApConfiguration config = mWifiManager.getSoftApConfiguration();
        if (config == null) {
            return;
        }
        if (mWifiHotspotRepository == null) {
            return;
        }
        try {
            SoftApConfiguration.Builder builder = new SoftApConfiguration.Builder(config);
            builder.setMaxChannelBandwidth(
                    keep160MhzEnabled
                            ? SoftApInfo.CHANNEL_WIDTH_160MHZ
                            : SoftApInfo.CHANNEL_WIDTH_AUTO);
            if (WifiTetherChannelPreferenceController.AUTO_VALUE.equals(option.value)) {
                if (keep160MhzEnabled) {
                    set5gAutoChannel(builder);
                } else {
                    resetChannelIfAutomatic(builder, config);
                }
            } else {
                builder.setChannel(option.channel, option.band);
            }
            mWifiHotspotRepository.setSoftApConfiguration(builder.build());
            mSelectedValue = option.value;
            refreshPreferences();
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Failed to update hotspot channel: " + option.value, e);
        }
    }

    private ChannelOption findOption(String value) {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return null;
        }
        Set<String> values = new HashSet<>();
        for (BandOption bandOption : BAND_OPTIONS) {
            for (ChannelOption option : getChannelOptions(bandOption, values)) {
                if (option.value.equals(value)) {
                    return option;
                }
            }
        }
        return null;
    }

    private void resetChannelIfAutomatic(SoftApConfiguration.Builder builder,
            SoftApConfiguration currentConfig) {
        SparseIntArray channels = currentConfig.getChannels();
        SparseIntArray autoChannels = new SparseIntArray(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            autoChannels.put(channels.keyAt(i), 0);
        }
        if (autoChannels.size() > 0) {
            builder.setChannels(autoChannels);
        }
    }

    private void set5gAutoChannel(SoftApConfiguration.Builder builder) {
        SparseIntArray channels = new SparseIntArray(1);
        channels.put(SoftApConfiguration.BAND_5GHZ, 0);
        builder.setChannels(channels);
    }

    private String getChannelValue(SoftApConfiguration config) {
        if (config == null) {
            return WifiTetherChannelPreferenceController.AUTO_VALUE;
        }
        SparseIntArray channels = config.getChannels();
        if (channels.size() == 1 && channels.valueAt(0) > 0) {
            return ChannelOption.encode(channels.keyAt(0), channels.valueAt(0));
        }
        return WifiTetherChannelPreferenceController.AUTO_VALUE;
    }

    private String getChannelLabel(String bandLabel, int channel, boolean supports160Mhz) {
        String label = getString(R.string.wifi_tether_channel_summary, bandLabel, channel);
        return supports160Mhz
                ? getString(R.string.wifi_tether_channel_160mhz_summary, label)
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

    private static final class BandOption {
        final int scannerBand;
        final int softApBand;

        BandOption(int scannerBand, int softApBand) {
            this.scannerBand = scannerBand;
            this.softApBand = softApBand;
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
