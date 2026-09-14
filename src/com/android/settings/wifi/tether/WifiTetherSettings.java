/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_CHANGED_ACTION;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.settings.wifi.WifiUtils.canShowWifiHotspot;

import android.annotation.SuppressLint;
import android.app.settings.SettingsEnums;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.dashboard.RestrictedDashboardFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.widget.SettingsMainSwitchBar;
import com.android.settings.wifi.WifiUtils;
import com.android.settings.wifi.repository.SharedConnectivityRepository;
import com.android.settingslib.TetherUtil;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.utils.ThreadUtils;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

// LINT.IfChange
@SearchIndexable
public class WifiTetherSettings extends RestrictedDashboardFragment
        implements WifiTetherBasePreferenceController.OnTetherConfigUpdateListener {

    private static final String TAG = "WifiTetherSettings";
    private static final IntentFilter TETHER_STATE_CHANGE_FILTER;
    private static final String KEY_WIFI_TETHER_SCREEN = "wifi_tether_settings_screen";
    private static final String KEY_WIFI_TETHER_NETWORK_SETTINGS_CATEGORY =
            "wifi_tether_network_settings_category";
    private static final String KEY_WIFI_TETHER_DEVICES_USAGE_CATEGORY =
            "wifi_tether_devices_usage_category";
    private static final String KEY_WIFI_TETHER_ADVANCED_SETTINGS_CATEGORY =
            "wifi_tether_advanced_settings_category";
    private static final long ONE_DAY_MILLIS = DateUtils.DAY_IN_MILLIS;

    @VisibleForTesting
    static final String KEY_WIFI_TETHER_NETWORK_NAME = "wifi_tether_network_name";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_SECURITY = "wifi_tether_security";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_NETWORK_PASSWORD = "wifi_tether_network_password";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_AUTO_OFF = "wifi_tether_auto_turn_off";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_MAXIMIZE_COMPATIBILITY =
            WifiTetherMaximizeCompatibilityPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_CONNECTED_DEVICES = "wifi_tether_connected_devices";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_MAX_CLIENTS =
            WifiTetherMaxClientsPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_CHANNEL =
            WifiTetherChannelPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_5G_160MHZ =
            WifiTether160MhzPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_HOTSPOT_DETAILS = "wifi_tether_hotspot_details";
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_DATA_LIMIT =
            WifiTetherDataLimitPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_TETHER_WIFI_VERSION =
            WifiTetherWifiVersionPreferenceController.PREF_KEY;
    @VisibleForTesting
    static final String KEY_WIFI_HOTSPOT_SECURITY = "wifi_hotspot_security";
    @VisibleForTesting
    static final String KEY_WIFI_HOTSPOT_SPEED = "wifi_hotspot_speed";
    @VisibleForTesting
    static final String KEY_INSTANT_HOTSPOT = "wifi_hotspot_instant";

    @VisibleForTesting
    SettingsMainSwitchBar mMainSwitchBar;
    private WifiTetherSwitchBarController mSwitchBarController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDevicesUsageRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateDevicesUsageSummary();
            mHandler.postDelayed(this, 10_000L);
        }
    };
    private int mDevicesUsageRefreshGeneration;
    @VisibleForTesting
    WifiTetherSSIDPreferenceController mSSIDPreferenceController;
    @VisibleForTesting
    WifiTetherPasswordPreferenceController mPasswordPreferenceController;
    @VisibleForTesting
    WifiTetherSecurityPreferenceController mSecurityPreferenceController;
    @VisibleForTesting
    WifiTetherMaximizeCompatibilityPreferenceController mMaxCompatibilityPrefController;
    @VisibleForTesting
    WifiTetherAutoOffPreferenceController mWifiTetherAutoOffPreferenceController;
    @VisibleForTesting
    WifiTetherMaxClientsPreferenceController mMaxClientsPreferenceController;
    @VisibleForTesting
    WifiTetherChannelPreferenceController mChannelPreferenceController;
    @VisibleForTesting
    WifiTether160MhzPreferenceController m160MhzPreferenceController;
    @VisibleForTesting
    WifiTetherWifiVersionPreferenceController mWifiVersionPreferenceController;

    @VisibleForTesting
    boolean mUnavailable;
    private WifiRestriction mWifiRestriction;
    @VisibleForTesting
    TetherChangeReceiver mTetherChangeReceiver;

    @VisibleForTesting
    WifiTetherViewModel mWifiTetherViewModel;
    @VisibleForTesting
    Preference mWifiHotspotSecurity;
    @VisibleForTesting
    Preference mWifiHotspotSpeed;
    @VisibleForTesting
    Preference mInstantHotspot;
    private Preference mDevicesUsagePreference;
    private Preference mHotspotDetailsPreference;
    private WifiManager mWifiManager;
    private boolean mIsSoftApInfoCallbackRegistered;
    private final List<SoftApInfo> mSoftApInfos = new ArrayList<>();
    private final WifiManager.SoftApCallback mSoftApInfoCallback =
            new WifiManager.SoftApCallback() {
                @Override
                public void onInfoChanged(@NonNull SoftApInfo softApInfo) {
                    mSoftApInfos.clear();
                    mSoftApInfos.add(softApInfo);
                    updateHotspotDetailsSummary();
                }

                @Override
                public void onInfoChanged(@NonNull List<SoftApInfo> softApInfoList) {
                    mSoftApInfos.clear();
                    mSoftApInfos.addAll(softApInfoList);
                    updateHotspotDetailsSummary();
                }
            };

    static {
        TETHER_STATE_CHANGE_FILTER = new IntentFilter(WIFI_AP_STATE_CHANGED_ACTION);
    }

    public WifiTetherSettings() {
        super(UserManager.DISALLOW_CONFIG_TETHERING);
        mWifiRestriction = new WifiRestriction();
    }

    public WifiTetherSettings(WifiRestriction wifiRestriction) {
        super(UserManager.DISALLOW_CONFIG_TETHERING);
        mWifiRestriction = wifiRestriction;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.WIFI_TETHER_SETTINGS;
    }

    @Override
    protected String getLogTag() {
        return "WifiTetherSettings";
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (!canShowWifiHotspot(getContext())) {
            Log.e(TAG, "can not launch Wi-Fi hotspot settings"
                    + " because the config is not set to show.");
            finish();
            return;
        }

        setIfOnlyAvailableForAdmins(true);
        mUnavailable = isUiRestricted() || !mWifiRestriction.isHotspotAvailable(getContext());
        if (mUnavailable) {
            return;
        }

        mDevicesUsagePreference = findPreference(KEY_WIFI_TETHER_CONNECTED_DEVICES);
        mHotspotDetailsPreference = findPreference(KEY_WIFI_TETHER_HOTSPOT_DETAILS);
        mWifiManager = getContext().getSystemService(WifiManager.class);
        setupHotspotDetailsPreference();

        mWifiTetherViewModel = FeatureFactory.getFeatureFactory().getWifiFeatureProvider()
                .getWifiTetherViewModel(this);
        if (mWifiTetherViewModel != null) {
            setupSpeedFeature(mWifiTetherViewModel.isSpeedFeatureAvailable());
            setupInstantHotspot(mWifiTetherViewModel.isInstantHotspotFeatureAvailable());
            mWifiTetherViewModel.getRestarting().observe(this, this::onRestartingChanged);
        }
    }

    @VisibleForTesting
    void setupSpeedFeature(boolean isSpeedFeatureAvailable) {
        mWifiHotspotSecurity = findPreference(KEY_WIFI_HOTSPOT_SECURITY);
        mWifiHotspotSpeed = findPreference(KEY_WIFI_HOTSPOT_SPEED);
        if (mWifiHotspotSecurity == null || mWifiHotspotSpeed == null) {
            return;
        }
        mWifiHotspotSecurity.setVisible(isSpeedFeatureAvailable);
        mWifiHotspotSpeed.setVisible(isSpeedFeatureAvailable);
        if (isSpeedFeatureAvailable) {
            mWifiTetherViewModel.getSecuritySummary().observe(this, this::onSecuritySummaryChanged);
            mWifiTetherViewModel.getSpeedSummary().observe(this, this::onSpeedSummaryChanged);
        }
    }

    @VisibleForTesting
    void setupInstantHotspot(boolean isFeatureAvailable) {
        if (!isFeatureAvailable) {
            return;
        }
        mInstantHotspot = findPreference(KEY_INSTANT_HOTSPOT);
        if (mInstantHotspot == null) {
            Log.e(TAG, "Failed to find Instant Hotspot preference:" + KEY_INSTANT_HOTSPOT);
            return;
        }
        mWifiTetherViewModel.getInstantHotspotSummary()
                .observe(this, this::onInstantHotspotChanged);
        mInstantHotspot.setOnPreferenceClickListener(p -> {
            mWifiTetherViewModel.launchInstantHotspotSettings();
            return true;
        });
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mTetherChangeReceiver = new TetherChangeReceiver();

        if (!isCatalystEnabled()) {
            mSSIDPreferenceController = use(WifiTetherSSIDPreferenceController.class);
            mWifiTetherAutoOffPreferenceController =
                    use(WifiTetherAutoOffPreferenceController.class);
        }
        mSecurityPreferenceController = use(WifiTetherSecurityPreferenceController.class);
        mPasswordPreferenceController = use(WifiTetherPasswordPreferenceController.class);
        mMaxCompatibilityPrefController =
                use(WifiTetherMaximizeCompatibilityPreferenceController.class);
        mMaxClientsPreferenceController = use(WifiTetherMaxClientsPreferenceController.class);
        mChannelPreferenceController = use(WifiTetherChannelPreferenceController.class);
        m160MhzPreferenceController = use(WifiTether160MhzPreferenceController.class);
        use(WifiTetherDataLimitPreferenceController.class);
        mWifiVersionPreferenceController = use(WifiTetherWifiVersionPreferenceController.class);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (mUnavailable) {
            return;
        }

        if (!isCatalystEnabled()) {
            // Assume we are in a SettingsActivity. This is only safe because we currently use
            // SettingsActivity as base for all preference fragments.
            final SettingsActivity activity = (SettingsActivity) getActivity();
            mMainSwitchBar = activity.getSwitchBar();
            mMainSwitchBar.setTitle(getString(R.string.use_wifi_hotsopt_main_switch_title));
            mSwitchBarController = new WifiTetherSwitchBarController(activity, mMainSwitchBar);
            getSettingsLifecycle().addObserver(mSwitchBarController);
            mMainSwitchBar.show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mWifiRestriction.isHotspotAvailable(getContext())) {
            getEmptyTextView().setText(R.string.not_allowed_by_ent);
            getPreferenceScreen().removeAll();
            return;
        }
        if (mUnavailable) {
            if (!isUiRestrictedByOnlyAdmin()) {
                getEmptyTextView()
                        .setText(com.android.settingslib.R.string.tethering_settings_not_available);
            }
            getPreferenceScreen().removeAll();
            return;
        }
        final Context context = getContext();
        if (context != null) {
            context.registerReceiver(mTetherChangeReceiver, TETHER_STATE_CHANGE_FILTER,
                    Context.RECEIVER_EXPORTED_UNAUDITED);
            registerSoftApInfoCallback(context);
            // The intent WIFI_AP_STATE_CHANGED_ACTION is not sticky intent anymore after SC-V2
            // Handle the initial state after register the receiver.
            updateDisplayWithNewConfig();
            WifiTetherDataLimitService.updateMonitoring(context);
            mHandler.removeCallbacks(mDevicesUsageRefreshRunnable);
            mHandler.post(mDevicesUsageRefreshRunnable);
        }
        mWifiTetherViewModel.refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mUnavailable) {
            return;
        }
        final Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(mTetherChangeReceiver);
            unregisterSoftApInfoCallback();
        }
        mDevicesUsageRefreshGeneration++;
        mHandler.removeCallbacks(mDevicesUsageRefreshRunnable);
    }

    protected void onSecuritySummaryChanged(Integer securityResId) {
        mWifiHotspotSecurity.setSummary(securityResId);
    }

    protected void onSpeedSummaryChanged(Integer summaryResId) {
        mWifiHotspotSpeed.setSummary(summaryResId);
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.wifi_tether_settings;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        return buildPreferenceControllers(context, this::onTetherConfigUpdated);
    }

    private static List<AbstractPreferenceController> buildPreferenceControllers(Context context,
            WifiTetherBasePreferenceController.OnTetherConfigUpdateListener listener) {
        final List<AbstractPreferenceController> controllers = new ArrayList<>();
        controllers.add(new WifiTetherSSIDPreferenceController(context, listener));
        controllers.add(new WifiTetherSecurityPreferenceController(context, listener));
        controllers.add(new WifiTetherPasswordPreferenceController(context, listener));
        controllers.add(
                new WifiTetherAutoOffPreferenceController(context, KEY_WIFI_TETHER_AUTO_OFF));
        controllers.add(new WifiTetherMaximizeCompatibilityPreferenceController(context, listener));
        controllers.add(new WifiTetherMaxClientsPreferenceController(context, listener));
        controllers.add(new WifiTetherChannelPreferenceController(context, listener));
        controllers.add(new WifiTether160MhzPreferenceController(context, listener));
        controllers.add(new WifiTetherDataLimitPreferenceController(context, listener));
        controllers.add(new WifiTetherWifiVersionPreferenceController(context, listener));
        return controllers;
    }

    @Override
    public void onTetherConfigUpdated(AbstractPreferenceController context) {
        final SoftApConfiguration config = buildNewConfig();
        mPasswordPreferenceController.setSecurityType(config.getSecurityType());

        mWifiTetherViewModel.setSoftApConfiguration(config);
    }

    @VisibleForTesting
    void onRestartingChanged(Boolean restarting) {
        if (!isCatalystEnabled()) {
            mMainSwitchBar.setVisibility((restarting) ? INVISIBLE : VISIBLE);
        }
        setLoading(restarting, false);
    }

    @VisibleForTesting
    void onInstantHotspotChanged(String summary) {
        if (summary == null) {
            mInstantHotspot.setVisible(false);
            return;
        }
        mInstantHotspot.setVisible(true);
        mInstantHotspot.setSummary(summary);
    }

    @VisibleForTesting
    SoftApConfiguration buildNewConfig() {
        SoftApConfiguration currentConfig = mWifiTetherViewModel.getSoftApConfiguration();
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(currentConfig);
        if (!isCatalystEnabled()) {
            configBuilder.setSsid(mSSIDPreferenceController.getSSID());
            configBuilder.setAutoShutdownEnabled(
                    mWifiTetherAutoOffPreferenceController.isEnabled());
        }
        int securityType =
                mWifiTetherViewModel.isSpeedFeatureAvailable()
                        ? currentConfig.getSecurityType()
                        : mSecurityPreferenceController.getSecurityType();
        String passphrase =
                securityType == SoftApConfiguration.SECURITY_TYPE_OPEN
                        ? null
                        : mPasswordPreferenceController.getPasswordValidated(securityType);
        configBuilder.setPassphrase(passphrase, securityType);
        boolean is160MhzEnabled = m160MhzPreferenceController != null
                && m160MhzPreferenceController.is5g160MhzEnabled();
        if (mChannelPreferenceController != null) {
            mChannelPreferenceController.setRequire5g160Mhz(is160MhzEnabled);
            mChannelPreferenceController.resetChannelIfAutomatic(configBuilder, currentConfig);
        }
        if (!mWifiTetherViewModel.isSpeedFeatureAvailable()) {
            mMaxCompatibilityPrefController.setupMaximizeCompatibility(configBuilder);
        }
        if (mMaxClientsPreferenceController != null) {
            mMaxClientsPreferenceController.setupMaxNumberOfClients(configBuilder);
        }
        if (mWifiVersionPreferenceController != null) {
            mWifiVersionPreferenceController.setupWifiVersion(configBuilder);
        }
        if (m160MhzPreferenceController != null) {
            m160MhzPreferenceController.setup5g160MhzMode(configBuilder);
        }
        if (mChannelPreferenceController != null) {
            mChannelPreferenceController.setupFixedChannel(configBuilder);
        }
        return configBuilder.build();
    }

    private void updateDisplayWithNewConfig() {
        if (!isCatalystEnabled()) {
            use(WifiTetherSSIDPreferenceController.class).updateDisplay();
        }
        use(WifiTetherSecurityPreferenceController.class).updateDisplay();
        use(WifiTetherPasswordPreferenceController.class).updateDisplay();
        use(WifiTetherMaximizeCompatibilityPreferenceController.class).updateDisplay();
        use(WifiTetherMaxClientsPreferenceController.class).updateDisplay();
        use(WifiTether160MhzPreferenceController.class).updateDisplay();
        if (mChannelPreferenceController != null && m160MhzPreferenceController != null) {
            mChannelPreferenceController.setRequire5g160Mhz(
                    m160MhzPreferenceController.is5g160MhzEnabled());
        }
        use(WifiTetherChannelPreferenceController.class).updateDisplay();
        use(WifiTetherDataLimitPreferenceController.class).updateDisplay();
        use(WifiTetherWifiVersionPreferenceController.class).updateDisplay();
        updateDevicesUsageSummary();
        updateHotspotDetailsSummary();
    }

    private void updateDevicesUsageSummary() {
        if (mDevicesUsagePreference == null || getContext() == null) {
            return;
        }
        final Context appContext = getContext().getApplicationContext();
        final int generation = ++mDevicesUsageRefreshGeneration;
        ThreadUtils.postOnBackgroundThread(() -> {
            long now = System.currentTimeMillis();
            long usageBytes = WifiTetherSettingsStore.getTetheringUsage(
                    appContext, now - ONE_DAY_MILLIS, now).getTotalBytes();
            ThreadUtils.postOnMainThread(() -> {
                if (!isAdded() || generation != mDevicesUsageRefreshGeneration) {
                    return;
                }
                mDevicesUsagePreference.setSummary(getString(
                        R.string.wifi_tether_devices_usage_summary_with_usage,
                        Formatter.formatFileSize(appContext, usageBytes)));
            });
        });
    }

    private void setupHotspotDetailsPreference() {
        if (mHotspotDetailsPreference == null) {
            return;
        }
        mHotspotDetailsPreference.setOnPreferenceClickListener(preference -> {
            showHotspotDetailsDialog();
            return true;
        });
        updateHotspotDetailsSummary();
    }

    private void registerSoftApInfoCallback(Context context) {
        if (mWifiManager == null || mIsSoftApInfoCallbackRegistered) {
            return;
        }
        try {
            mWifiManager.registerSoftApCallback(context.getMainExecutor(), mSoftApInfoCallback);
            mIsSoftApInfoCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to register Soft AP info callback", e);
        }
    }

    private void unregisterSoftApInfoCallback() {
        if (mWifiManager == null || !mIsSoftApInfoCallbackRegistered) {
            return;
        }
        try {
            mWifiManager.unregisterSoftApCallback(mSoftApInfoCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to unregister Soft AP info callback", e);
        }
        mIsSoftApInfoCallbackRegistered = false;
    }

    private void updateHotspotDetailsSummary() {
        if (mHotspotDetailsPreference == null || getContext() == null) {
            return;
        }
        SoftApConfiguration config =
                mWifiManager != null ? mWifiManager.getSoftApConfiguration() : null;
        String summary = formatConfiguredSsid(config);
        String channel = formatLiveChannel();
        if (!TextUtils.isEmpty(channel)) {
            summary += " · " + channel;
        }
        mHotspotDetailsPreference.setSummary(summary);
    }

    private void showHotspotDetailsDialog() {
        SoftApConfiguration config =
                mWifiManager != null ? mWifiManager.getSoftApConfiguration() : null;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wifi_tether_hotspot_details_title)
                .setMessage(buildHotspotDetailsMessage(config))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String buildHotspotDetailsMessage(@Nullable SoftApConfiguration config) {
        StringBuilder builder = new StringBuilder();
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_ssid,
                formatConfiguredSsid(config));
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_bssid,
                formatLiveBssid());
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_txpower,
                getString(R.string.wifi_tether_hotspot_details_unavailable));
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_band,
                firstNonEmpty(formatLiveBand(), formatConfiguredBands(config)));
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_channel,
                firstNonEmpty(formatLiveChannel(), formatConfiguredChannels(config)));
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_bandwidth,
                firstNonEmpty(formatLiveBandwidth(), formatConfiguredBandwidth(config)));
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_mode,
                formatLiveMode());
        appendDetail(
                builder,
                R.string.wifi_tether_hotspot_details_country_code,
                formatCountryCode());
        return builder.toString();
    }

    private void appendDetail(StringBuilder builder, int labelResId, String value) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(getString(labelResId))
                .append(": ")
                .append(TextUtils.isEmpty(value)
                        ? getString(R.string.wifi_tether_hotspot_details_unavailable)
                        : value);
    }

    @SuppressWarnings("deprecation")
    private String formatConfiguredSsid(@Nullable SoftApConfiguration config) {
        if (config == null || TextUtils.isEmpty(config.getSsid())) {
            return getString(R.string.wifi_tether_hotspot_details_unavailable);
        }
        return config.getSsid();
    }

    private String formatLiveBssid() {
        StringJoiner joiner = new StringJoiner(", ");
        for (SoftApInfo info : mSoftApInfos) {
            if (info.getBssid() != null) {
                joiner.add(info.getBssid().toString());
            }
        }
        return joiner.toString();
    }

    private String formatLiveBand() {
        StringJoiner joiner = new StringJoiner(", ");
        for (SoftApInfo info : mSoftApInfos) {
            String band = getBandLabelFromFrequency(info.getFrequency());
            if (!TextUtils.isEmpty(band)) {
                joiner.add(band);
            }
        }
        return joiner.toString();
    }

    private String formatLiveChannel() {
        StringJoiner joiner = new StringJoiner(", ");
        for (SoftApInfo info : mSoftApInfos) {
            int channel = ScanResult.convertFrequencyMhzToChannelIfSupported(info.getFrequency());
            if (channel != ScanResult.UNSPECIFIED) {
                String band = getBandLabelFromFrequency(info.getFrequency());
                joiner.add(TextUtils.isEmpty(band)
                        ? String.valueOf(channel)
                        : getString(R.string.wifi_tether_channel_summary, band, channel));
            }
        }
        return joiner.toString();
    }

    private String formatLiveBandwidth() {
        StringJoiner joiner = new StringJoiner(", ");
        for (SoftApInfo info : mSoftApInfos) {
            String bandwidth = formatBandwidth(info.getBandwidth());
            if (!TextUtils.isEmpty(bandwidth)) {
                joiner.add(bandwidth);
            }
        }
        return joiner.toString();
    }

    private String formatLiveMode() {
        StringJoiner joiner = new StringJoiner(", ");
        for (SoftApInfo info : mSoftApInfos) {
            String mode = formatWifiStandard(info.getWifiStandard());
            if (!TextUtils.isEmpty(mode)) {
                joiner.add(mode);
            }
        }
        return joiner.toString();
    }

    private String formatConfiguredBands(@Nullable SoftApConfiguration config) {
        if (config == null) {
            return "";
        }
        SparseIntArray channels = config.getChannels();
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < channels.size(); i++) {
            joiner.add(getBandLabel(channels.keyAt(i)));
        }
        return joiner.toString();
    }

    private String formatConfiguredChannels(@Nullable SoftApConfiguration config) {
        if (config == null) {
            return "";
        }
        SparseIntArray channels = config.getChannels();
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < channels.size(); i++) {
            String band = getBandLabel(channels.keyAt(i));
            int channel = channels.valueAt(i);
            joiner.add(channel > 0
                    ? getString(R.string.wifi_tether_channel_summary, band, channel)
                    : band + " " + getString(R.string.wifi_tether_hotspot_details_auto));
        }
        return joiner.toString();
    }

    @SuppressLint("NewApi")
    private String formatConfiguredBandwidth(@Nullable SoftApConfiguration config) {
        if (config == null) {
            return "";
        }
        return formatBandwidth(config.getMaxChannelBandwidth());
    }

    private String formatCountryCode() {
        if (mWifiManager == null) {
            return "";
        }
        try {
            return mWifiManager.getCountryCode();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read hotspot country code", e);
            return "";
        }
    }

    private String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    private String getBandLabelFromFrequency(int frequencyMhz) {
        if (frequencyMhz >= 2400 && frequencyMhz < 2500) {
            return getBandLabel(SoftApConfiguration.BAND_2GHZ);
        } else if (frequencyMhz >= 4900 && frequencyMhz < 5900) {
            return getBandLabel(SoftApConfiguration.BAND_5GHZ);
        } else if (frequencyMhz >= 5925 && frequencyMhz < 7125) {
            return getBandLabel(SoftApConfiguration.BAND_6GHZ);
        } else if (frequencyMhz >= 56000 && frequencyMhz < 71000) {
            return getBandLabel(SoftApConfiguration.BAND_60GHZ);
        }
        return "";
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
                return getString(R.string.wifi_tether_hotspot_details_unavailable);
        }
    }

    private String formatBandwidth(int bandwidth) {
        switch (bandwidth) {
            case SoftApInfo.CHANNEL_WIDTH_AUTO:
                return getString(R.string.wifi_tether_hotspot_details_auto);
            case SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT:
                return "20 MHz (no HT)";
            case SoftApInfo.CHANNEL_WIDTH_20MHZ:
                return "20 MHz";
            case SoftApInfo.CHANNEL_WIDTH_40MHZ:
                return "40 MHz";
            case SoftApInfo.CHANNEL_WIDTH_80MHZ:
                return "80 MHz";
            case SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return "80+80 MHz";
            case SoftApInfo.CHANNEL_WIDTH_160MHZ:
                return "160 MHz";
            case SoftApInfo.CHANNEL_WIDTH_320MHZ:
                return "320 MHz";
            default:
                return "";
        }
    }

    private String formatWifiStandard(int wifiStandard) {
        switch (wifiStandard) {
            case ScanResult.WIFI_STANDARD_11N:
                return "Wi-Fi 4";
            case ScanResult.WIFI_STANDARD_11AC:
                return "Wi-Fi 5";
            case ScanResult.WIFI_STANDARD_11AX:
                return "Wi-Fi 6";
            case ScanResult.WIFI_STANDARD_11AD:
                return "WiGig";
            case ScanResult.WIFI_STANDARD_11BE:
                return "Wi-Fi 7";
            default:
                return "";
        }
    }

    @Override
    public @Nullable String getPreferenceScreenBindingKey(@NonNull Context context) {
        return WifiHotspotScreen.KEY;
    }

    public static final SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new SearchIndexProvider(R.xml.wifi_tether_settings);

    @VisibleForTesting
    static class SearchIndexProvider extends BaseSearchIndexProvider {

        private final WifiRestriction mWifiRestriction;
        private final boolean mIsInstantHotspotEnabled;

        SearchIndexProvider(int xmlRes) {
            super(xmlRes);
            mWifiRestriction = new WifiRestriction();
            mIsInstantHotspotEnabled = SharedConnectivityRepository.isDeviceConfigEnabled();
        }

        @VisibleForTesting
        SearchIndexProvider(int xmlRes, WifiRestriction wifiRestriction,
                boolean isInstantHotspotEnabled) {
            super(xmlRes);
            mWifiRestriction = wifiRestriction;
            mIsInstantHotspotEnabled = isInstantHotspotEnabled;
        }

        @Override
        public List<String> getNonIndexableKeys(Context context) {
            final List<String> keys = super.getNonIndexableKeys(context);

            if (!mWifiRestriction.isTetherAvailable(context)
                    || !mWifiRestriction.isHotspotAvailable(context)) {
                keys.add(KEY_WIFI_TETHER_NETWORK_SETTINGS_CATEGORY);
                keys.add(KEY_WIFI_TETHER_NETWORK_NAME);
                keys.add(KEY_WIFI_TETHER_SECURITY);
                keys.add(KEY_WIFI_HOTSPOT_SECURITY);
                keys.add(KEY_WIFI_TETHER_NETWORK_PASSWORD);
                keys.add(KEY_WIFI_TETHER_AUTO_OFF);
                keys.add(KEY_WIFI_TETHER_MAXIMIZE_COMPATIBILITY);
                keys.add(KEY_WIFI_TETHER_DEVICES_USAGE_CATEGORY);
                keys.add(KEY_WIFI_TETHER_CONNECTED_DEVICES);
                keys.add(KEY_WIFI_TETHER_MAX_CLIENTS);
                keys.add(KEY_WIFI_TETHER_DATA_LIMIT);
                keys.add(KEY_WIFI_TETHER_ADVANCED_SETTINGS_CATEGORY);
                keys.add(KEY_WIFI_TETHER_CHANNEL);
                keys.add(KEY_WIFI_TETHER_5G_160MHZ);
                keys.add(KEY_WIFI_TETHER_WIFI_VERSION);
                keys.add(KEY_WIFI_TETHER_HOTSPOT_DETAILS);
                keys.add(KEY_WIFI_HOTSPOT_SPEED);
                keys.add(KEY_INSTANT_HOTSPOT);
            } else {
                if (!isSpeedFeatureAvailable()) {
                    keys.add(KEY_WIFI_HOTSPOT_SECURITY);
                    keys.add(KEY_WIFI_HOTSPOT_SPEED);
                }
                if (!mIsInstantHotspotEnabled) {
                    keys.add(KEY_INSTANT_HOTSPOT);
                }
            }

            // Remove duplicate
            keys.add(KEY_WIFI_TETHER_SCREEN);
            return keys;
        }

        @Override
        protected boolean isPageSearchEnabled(Context context) {
            if (context == null) {
                return false;
            }
            UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager == null || !userManager.isAdminUser()) {
                return false;
            }
            return WifiUtils.canShowWifiHotspot(context);
        }

        @Override
        public List<AbstractPreferenceController> createPreferenceControllers(Context context) {
            return buildPreferenceControllers(context, null /* listener */);
        }

        @VisibleForTesting
        boolean isSpeedFeatureAvailable() {
            return FeatureFactory.getFeatureFactory().getWifiFeatureProvider()
                    .getWifiHotspotRepository().isSpeedFeatureAvailable();
        }
    }

    @VisibleForTesting
    static class WifiRestriction {
        public boolean isTetherAvailable(@Nullable Context context) {
            if (context == null) return true;
            return TetherUtil.isTetherAvailable(context);
        }

        public boolean isHotspotAvailable(@Nullable Context context) {
            if (context == null) return true;
            return WifiEnterpriseRestrictionUtils.isWifiTetheringAllowed(context);
        }
    }

    @VisibleForTesting
    class TetherChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "updating display config due to receiving broadcast action " + action);
            updateDisplayWithNewConfig();
        }
    }
}
// LINT.ThenChange(WifiHotspotScreen.kt)
