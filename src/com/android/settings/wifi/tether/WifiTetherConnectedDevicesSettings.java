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

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.net.MacAddress;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settingslib.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shows current and historical tethering clients, usage and Wi-Fi client controls. */
public class WifiTetherConnectedDevicesSettings extends DashboardFragment {

    private static final String TAG = "WifiTetherDevicesUsage";
    private static final String KEY_SESSION_USAGE = "wifi_tether_session_usage";
    private static final String KEY_CONNECTED_DEVICES_CATEGORY =
            "wifi_tether_connected_devices_category";
    private static final String KEY_HISTORY_DEVICES_CATEGORY =
            "wifi_tether_history_devices_category";
    private static final String KEY_BLOCKED_DEVICES_CATEGORY =
            "wifi_tether_blocked_devices_category";
    private static final String KEY_FOOTER = "wifi_tether_connected_devices_footer";
    private static final long ONE_DAY_MILLIS = DateUtils.DAY_IN_MILLIS;

    private WifiManager mWifiManager;
    private TetheringManager mTetheringManager;
    private WifiTetherClientRepository mClientRepository;
    private Preference mSessionUsagePreference;
    private PreferenceCategory mConnectedDevicesCategory;
    private PreferenceCategory mHistoryDevicesCategory;
    private PreferenceCategory mBlockedDevicesCategory;
    private Preference mFooterPreference;
    private final List<WifiClient> mConnectedWifiClients = new ArrayList<>();
    private final List<TetheredClient> mTetheredClients = new ArrayList<>();
    private List<WifiTetherClientRepository.ClientRecord> mClientRecords = new ArrayList<>();
    private Map<Integer, WifiTetherClientRepository.UsageBytes> mSessionUsageByTag =
            Collections.emptyMap();
    private long mRecentUsageBytes = -1L;
    private boolean mIsClientManagementSupported = true;
    private boolean mIsWifiCallbackRegistered;
    private boolean mIsTetheringCallbackRegistered;
    private int mRefreshGeneration;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPeriodicRefreshRunnable =
            new Runnable() {
                @Override
                public void run() {
                    persistAndRefresh();
                    mHandler.postDelayed(this, 10_000L);
                }
            };

    private final WifiManager.SoftApCallback mSoftApCallback =
            new WifiManager.SoftApCallback() {
                @Override
                public void onConnectedClientsChanged(@NonNull List<WifiClient> clients) {
                    mConnectedWifiClients.clear();
                    mConnectedWifiClients.addAll(clients);
                    persistAndRefresh();
                }

                @Override
                public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
                    mIsClientManagementSupported =
                            softApCapability.areFeaturesSupported(
                                    SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT);
                    refreshPreferences();
                }
            };

    private final TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onClientsChanged(Collection<TetheredClient> clients) {
                    mTetheredClients.clear();
                    mTetheredClients.addAll(clients);
                    persistAndRefresh();
                }
            };

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.wifi_tether_connected_devices;
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
        mWifiManager = requireContext().getSystemService(WifiManager.class);
        mTetheringManager = requireContext().getSystemService(TetheringManager.class);
        mClientRepository = WifiTetherClientRepository.getInstance(requireContext());
        mSessionUsagePreference = findPreference(KEY_SESSION_USAGE);
        mConnectedDevicesCategory = findPreference(KEY_CONNECTED_DEVICES_CATEGORY);
        mHistoryDevicesCategory = findPreference(KEY_HISTORY_DEVICES_CATEGORY);
        mBlockedDevicesCategory = findPreference(KEY_BLOCKED_DEVICES_CATEGORY);
        mFooterPreference = findPreference(KEY_FOOTER);
        refreshPreferences();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mWifiManager != null && !mIsWifiCallbackRegistered) {
            try {
                mWifiManager.registerSoftApCallback(
                        requireContext().getMainExecutor(), mSoftApCallback);
                mIsWifiCallbackRegistered = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to register Soft AP callback", e);
            }
        }
        if (mTetheringManager != null && !mIsTetheringCallbackRegistered) {
            try {
                mTetheringManager.registerTetheringEventCallback(
                        requireContext().getMainExecutor(), mTetheringCallback);
                mIsTetheringCallbackRegistered = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to register tethering callback", e);
            }
        }
        WifiTetherDataLimitService.updateMonitoring(requireContext());
        mHandler.removeCallbacks(mPeriodicRefreshRunnable);
        mHandler.post(mPeriodicRefreshRunnable);
    }

    @Override
    public void onStop() {
        mRefreshGeneration++;
        mHandler.removeCallbacks(mPeriodicRefreshRunnable);
        if (mWifiManager != null && mIsWifiCallbackRegistered) {
            try {
                mWifiManager.unregisterSoftApCallback(mSoftApCallback);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to unregister Soft AP callback", e);
            }
            mIsWifiCallbackRegistered = false;
        }
        if (mTetheringManager != null && mIsTetheringCallbackRegistered) {
            try {
                mTetheringManager.unregisterTetheringEventCallback(mTetheringCallback);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unable to unregister tethering callback", e);
            }
            mIsTetheringCallbackRegistered = false;
        }
        super.onStop();
    }

    private void persistAndRefresh() {
        final int generation = ++mRefreshGeneration;
        final Context appContext = requireContext().getApplicationContext();
        final List<TetheredClient> tetheredClients = new ArrayList<>(mTetheredClients);
        final List<WifiClient> wifiClients = new ArrayList<>(mConnectedWifiClients);
        ThreadUtils.postOnBackgroundThread(
                () -> {
                    mClientRepository.recordClients(tetheredClients);
                    for (WifiClient client : wifiClients) {
                        mClientRepository.recordWifiClient(client.getMacAddress());
                    }
                    mClientRepository.refreshUsage();
                    List<WifiTetherClientRepository.ClientRecord> records =
                            mClientRepository.getClients();
                    long now = System.currentTimeMillis();
                    long sessionStart = WifiTetherSettingsStore.BASELINE_UNSET;
                    if (isHotspotActive(appContext)) {
                        sessionStart =
                                WifiTetherSettingsStore.ensureHotspotSessionStarted(appContext);
                    } else {
                        WifiTetherSettingsStore.clearHotspotSession(appContext);
                    }
                    Map<Integer, WifiTetherClientRepository.UsageBytes> sessionUsageByTag =
                            sessionStart == WifiTetherSettingsStore.BASELINE_UNSET
                                    ? Collections.emptyMap()
                                    : mClientRepository.queryTaggedUsage(sessionStart, now);
                    long recentUsage = sumUsage(
                            mClientRepository.queryTaggedUsage(
                                    now - ONE_DAY_MILLIS, now).values());
                    ThreadUtils.postOnMainThread(
                            () -> {
                                if (!isAdded() || generation != mRefreshGeneration) {
                                    return;
                                }
                                mClientRecords = records;
                                mSessionUsageByTag = sessionUsageByTag;
                                mRecentUsageBytes = recentUsage;
                                refreshPreferences();
                            });
                });
    }

    private void refreshPreferences() {
        if (mConnectedDevicesCategory == null
                || mHistoryDevicesCategory == null
                || mBlockedDevicesCategory == null) {
            return;
        }
        if (mSessionUsagePreference != null) {
            mSessionUsagePreference.setTitle(R.string.wifi_tether_usage_details_title);
            mSessionUsagePreference.setSummary(
                    mRecentUsageBytes < 0L
                            ? getString(R.string.data_usage_tethering_usage_loading)
                            : getString(
                                    R.string.wifi_tether_usage_last_day_summary,
                                    Formatter.formatFileSize(requireContext(), mRecentUsageBytes)));
            mSessionUsagePreference.setSelectable(true);
            mSessionUsagePreference.setOnPreferenceClickListener(preference -> {
                WifiTetherUsageDetailsSettings.launch(
                        requireContext(), getMetricsCategory(), null);
                return true;
            });
        }
        refreshConnectedDevices();
        refreshHistoryDevices();
        refreshBlockedDevices();
        if (mFooterPreference != null) {
            mFooterPreference.setTitle(
                    mIsClientManagementSupported
                            ? R.string.wifi_tether_devices_usage_footer
                            : R.string.wifi_tether_feature_unavailable);
        }
    }

    private void refreshConnectedDevices() {
        mConnectedDevicesCategory.removeAll();
        Set<String> activeKeys = getActiveClientKeys();
        for (WifiTetherClientRepository.ClientRecord record : mClientRecords) {
            if (activeKeys.contains(
                    getClientKey(record.getTetheringType(), record.getMacAddress()))) {
                addClientPreference(mConnectedDevicesCategory, record, true);
            }
        }
        for (WifiClient client : mConnectedWifiClients) {
            String macAddress = client.getMacAddress().toString();
            if (findRecord(TetheringManager.TETHERING_WIFI, macAddress) == null) {
                addWifiFallbackPreference(macAddress);
            }
        }
        if (mConnectedDevicesCategory.getPreferenceCount() == 0) {
            addEmptyPreference(
                    mConnectedDevicesCategory, R.string.wifi_tether_connected_devices_empty);
        }
    }

    private void refreshHistoryDevices() {
        mHistoryDevicesCategory.removeAll();
        Set<String> activeKeys = getActiveClientKeys();
        Set<String> blockedMacs = getBlockedMacStrings();
        for (WifiTetherClientRepository.ClientRecord record : mClientRecords) {
            boolean isWifi =
                    WifiTetherClientRepository.normalizeTetheringType(record.getTetheringType())
                            == TetheringManager.TETHERING_WIFI;
            if (!activeKeys.contains(
                            getClientKey(record.getTetheringType(), record.getMacAddress()))
                    && (!isWifi || !blockedMacs.contains(record.getMacAddress()))) {
                addClientPreference(mHistoryDevicesCategory, record, false);
            }
        }
        if (mHistoryDevicesCategory.getPreferenceCount() == 0) {
            addEmptyPreference(mHistoryDevicesCategory, R.string.wifi_tether_history_devices_empty);
        }
    }

    private void refreshBlockedDevices() {
        mBlockedDevicesCategory.removeAll();
        List<MacAddress> blockedClients = getBlockedClientList();
        for (MacAddress macAddress : blockedClients) {
            WifiTetherClientRepository.ClientRecord record =
                    findRecord(TetheringManager.TETHERING_WIFI, macAddress.toString());
            Preference preference = new Preference(getPrefContext());
            preference.setTitle(record == null ? macAddress.toString() : getClientLabel(record));
            preference.setSummary(
                    record == null
                            ? getString(R.string.wifi_tether_client_blocked_summary)
                            : getString(
                                    R.string.wifi_tether_client_usage_summary,
                                    getTetheringTypeLabel(record.getTetheringType()),
                                    getString(R.string.wifi_tether_client_blocked_summary),
                                    Formatter.formatFileSize(
                                            requireContext(), record.getUsageBytes())));
            preference.setEnabled(mIsClientManagementSupported);
            preference.setOnPreferenceClickListener(
                    p -> {
                        showUnblockClientDialog(macAddress);
                        return true;
                    });
            mBlockedDevicesCategory.addPreference(preference);
        }
        if (blockedClients.isEmpty()) {
            addEmptyPreference(mBlockedDevicesCategory, R.string.wifi_tether_blocked_devices_empty);
        }
    }

    private void addClientPreference(
            PreferenceCategory category,
            WifiTetherClientRepository.ClientRecord record,
            boolean connected) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(getClientLabel(record));
        preference.setSummary(getClientSummary(record, connected));
        boolean isWifi =
                WifiTetherClientRepository.normalizeTetheringType(record.getTetheringType())
                        == TetheringManager.TETHERING_WIFI;
        preference.setEnabled(true);
        if (isWifi) {
            MacAddress macAddress = MacAddress.fromString(record.getMacAddress());
            preference.setOnPreferenceClickListener(
                    p -> {
                        if (connected) {
                            showConnectedClientActions(macAddress, getClientLabel(record), record);
                        } else {
                            showHistoryClientActions(macAddress, getClientLabel(record), record);
                        }
                        return true;
                    });
        } else {
            preference.setOnPreferenceClickListener(p -> {
                WifiTetherUsageDetailsSettings.launch(
                        requireContext(), getMetricsCategory(), record);
                return true;
            });
        }
        category.addPreference(preference);
    }

    private void addWifiFallbackPreference(String macAddress) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(macAddress);
        preference.setSummary(
                getString(
                        R.string.wifi_tether_client_connected_usage_summary,
                        getString(R.string.wifi_tether_connection_type_wifi),
                        Formatter.formatFileSize(requireContext(), 0L)));
        preference.setEnabled(mIsClientManagementSupported);
        preference.setOnPreferenceClickListener(
                p -> {
                    showConnectedClientActions(
                            MacAddress.fromString(macAddress), macAddress, null);
                    return true;
                });
        mConnectedDevicesCategory.addPreference(preference);
    }

    private String getClientSummary(
            WifiTetherClientRepository.ClientRecord record, boolean connected) {
        if (connected) {
            return getString(
                    R.string.wifi_tether_client_connected_usage_summary,
                    getTetheringTypeLabel(record.getTetheringType()),
                    Formatter.formatFileSize(requireContext(), getSessionUsageBytes(record)));
        }
        return getString(
                R.string.wifi_tether_client_history_actions_summary,
                getTetheringTypeLabel(record.getTetheringType()));
    }

    private long getSessionUsageBytes(WifiTetherClientRepository.ClientRecord record) {
        WifiTetherClientRepository.UsageBytes usage = mSessionUsageByTag.get(record.getStatsTag());
        return usage == null ? 0L : usage.getTotalBytes();
    }

    private static long sumUsage(
            Collection<WifiTetherClientRepository.UsageBytes> usageCollection) {
        long totalBytes = 0L;
        for (WifiTetherClientRepository.UsageBytes usage : usageCollection) {
            totalBytes += usage.getTotalBytes();
        }
        return totalBytes;
    }

    private static boolean isHotspotActive(Context context) {
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager == null) {
            return false;
        }
        int state = wifiManager.getWifiApState();
        return state == WifiManager.WIFI_AP_STATE_ENABLING
                || state == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    private String getClientLabel(WifiTetherClientRepository.ClientRecord record) {
        if (TextUtils.isEmpty(record.getHostname())) {
            return record.getMacAddress();
        }
        return getString(
                R.string.data_usage_tethering_client_label,
                record.getHostname(),
                record.getMacAddress());
    }

    private String getTetheringTypeLabel(int tetheringType) {
        switch (WifiTetherClientRepository.normalizeTetheringType(tetheringType)) {
            case TetheringManager.TETHERING_USB:
                return getString(R.string.tether_settings_title_usb);
            case TetheringManager.TETHERING_BLUETOOTH:
                return getString(R.string.tether_settings_title_bluetooth);
            case TetheringManager.TETHERING_ETHERNET:
                return getString(R.string.ethernet_tether_checkbox_text);
            case TetheringManager.TETHERING_WIFI:
            default:
                return getString(R.string.wifi_tether_connection_type_wifi);
        }
    }

    private Set<String> getActiveClientKeys() {
        Set<String> keys = new HashSet<>();
        for (TetheredClient client : mTetheredClients) {
            keys.add(getClientKey(client.getTetheringType(), client.getMacAddress().toString()));
        }
        for (WifiClient client : mConnectedWifiClients) {
            keys.add(
                    getClientKey(
                            TetheringManager.TETHERING_WIFI, client.getMacAddress().toString()));
        }
        return keys;
    }

    private static String getClientKey(int tetheringType, String macAddress) {
        return WifiTetherClientRepository.normalizeTetheringType(tetheringType) + ":" + macAddress;
    }

    private WifiTetherClientRepository.ClientRecord findRecord(
            int tetheringType, String macAddress) {
        for (WifiTetherClientRepository.ClientRecord record : mClientRecords) {
            if (getClientKey(record.getTetheringType(), record.getMacAddress())
                    .equals(getClientKey(tetheringType, macAddress))) {
                return record;
            }
        }
        return null;
    }

    private Set<String> getBlockedMacStrings() {
        Set<String> blocked = new HashSet<>();
        for (MacAddress address : getBlockedClientList()) {
            blocked.add(address.toString());
        }
        return blocked;
    }

    private void showConnectedClientActions(
            MacAddress macAddress, String label,
            WifiTetherClientRepository.ClientRecord record) {
        List<CharSequence> actions = new ArrayList<>();
        if (record != null) {
            actions.add(getString(R.string.wifi_tether_client_usage_history));
        }
        if (mIsClientManagementSupported) {
            actions.add(getString(R.string.wifi_tether_client_disconnect));
            actions.add(getString(R.string.wifi_tether_client_block));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(label)
                .setItems(
                        actions.toArray(new CharSequence[0]),
                        (dialog, which) -> {
                            CharSequence action = actions.get(which);
                            if (TextUtils.equals(
                                    action, getString(R.string.wifi_tether_client_usage_history))) {
                                WifiTetherUsageDetailsSettings.launch(
                                        requireContext(), getMetricsCategory(), record);
                            } else if (TextUtils.equals(
                                    action, getString(R.string.wifi_tether_client_disconnect))) {
                                disconnectClient(macAddress);
                            } else {
                                showBlockClientDialog(macAddress, label);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showHistoryClientActions(
            MacAddress macAddress, String label,
            WifiTetherClientRepository.ClientRecord record) {
        List<CharSequence> actions = new ArrayList<>();
        actions.add(getString(R.string.wifi_tether_client_usage_history));
        if (mIsClientManagementSupported) {
            actions.add(getString(R.string.wifi_tether_client_block));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(label)
                .setItems(
                        actions.toArray(new CharSequence[0]),
                        (dialog, which) -> {
                            CharSequence action = actions.get(which);
                            if (TextUtils.equals(
                                    action, getString(R.string.wifi_tether_client_usage_history))) {
                                WifiTetherUsageDetailsSettings.launch(
                                        requireContext(), getMetricsCategory(), record);
                            } else {
                                showBlockClientDialog(macAddress, label);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void disconnectClient(MacAddress macAddress) {
        if (mWifiManager == null || !mIsClientManagementSupported) {
            return;
        }
        boolean disconnected = false;
        try {
            disconnected = mWifiManager.disconnectTetheredHotspotClient(macAddress);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to disconnect hotspot client", e);
        }
        Toast.makeText(
                        requireContext(),
                        disconnected
                                ? R.string.wifi_tether_client_disconnected
                                : R.string.wifi_tether_client_disconnect_failed,
                        Toast.LENGTH_SHORT)
                .show();
        persistAndRefresh();
    }

    private void showBlockClientDialog(MacAddress macAddress, String label) {
        new AlertDialog.Builder(requireContext())
                .setTitle(label)
                .setMessage(R.string.wifi_tether_client_block_message)
                .setPositiveButton(
                        R.string.wifi_tether_client_block,
                        (dialog, which) -> setClientBlocked(macAddress, true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showUnblockClientDialog(MacAddress macAddress) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wifi_tether_client_unblock_title)
                .setMessage(R.string.wifi_tether_client_unblock_message)
                .setPositiveButton(
                        R.string.wifi_tether_client_unblock,
                        (dialog, which) -> setClientBlocked(macAddress, false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setClientBlocked(MacAddress macAddress, boolean blocked) {
        if (mWifiManager == null || !mIsClientManagementSupported) {
            return;
        }
        SoftApConfiguration config = mWifiManager.getSoftApConfiguration();
        if (config == null) {
            return;
        }
        List<MacAddress> blockedClients = new ArrayList<>(config.getBlockedClientList());
        if (blocked && !blockedClients.contains(macAddress)) {
            blockedClients.add(macAddress);
        } else if (!blocked) {
            blockedClients.remove(macAddress);
        }
        try {
            SoftApConfiguration newConfig =
                    new SoftApConfiguration.Builder(config)
                            .setBlockedClientList(blockedClients)
                            .build();
            mWifiManager.setSoftApConfiguration(newConfig);
            mClientRepository.recordWifiClient(macAddress);
            persistAndRefresh();
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Failed to update blocked client list", e);
        }
    }

    private List<MacAddress> getBlockedClientList() {
        SoftApConfiguration config =
                mWifiManager != null ? mWifiManager.getSoftApConfiguration() : null;
        return config != null ? new ArrayList<>(config.getBlockedClientList()) : new ArrayList<>();
    }

    private void addEmptyPreference(PreferenceCategory category, int titleResId) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(titleResId);
        preference.setSelectable(false);
        category.addPreference(preference);
    }
}
