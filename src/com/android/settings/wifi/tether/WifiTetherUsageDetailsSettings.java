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

import android.app.DatePickerDialog;
import android.app.settings.SettingsEnums;
import android.app.usage.NetworkStats;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.settings.R;
import com.android.settings.core.SubSettingLauncher;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.wifi.tether.WifiTetherClientRepository.ClientRecord;
import com.android.settings.wifi.tether.WifiTetherClientRepository.UsageBytes;
import com.android.settingslib.utils.ThreadUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shows tethering usage by time range and by tethered endpoint. */
public class WifiTetherUsageDetailsSettings extends DashboardFragment {

    public static final String ARG_TETHERING_TYPE = "tethering_type";
    public static final String ARG_MAC_ADDRESS = "mac_address";
    public static final String ARG_HOSTNAME = "hostname";

    private static final String TAG = "WifiTetherUsageDetails";
    private static final String KEY_DEVICE_IDENTITY = "wifi_tether_usage_device_identity";
    private static final String KEY_TIME_RANGE = "wifi_tether_usage_time_range";
    private static final String KEY_SUMMARY_CATEGORY = "wifi_tether_usage_summary_category";
    private static final String KEY_ACTIONS_CATEGORY = "wifi_tether_usage_actions_category";
    private static final String KEY_DEVICES_CATEGORY = "wifi_tether_usage_devices_category";
    private static final long ONE_DAY_MILLIS = DateUtils.DAY_IN_MILLIS;
    private static final int DATE_FORMAT = DateUtils.FORMAT_SHOW_DATE
            | DateUtils.FORMAT_SHOW_YEAR
            | DateUtils.FORMAT_ABBREV_MONTH;

    private WifiTetherClientRepository mClientRepository;
    private WifiManager mWifiManager;
    private TetheringManager mTetheringManager;
    private Preference mDeviceIdentityPreference;
    private Preference mTimeRangePreference;
    private PreferenceCategory mSummaryCategory;
    private PreferenceCategory mActionsCategory;
    private PreferenceCategory mDevicesCategory;
    private final List<WifiClient> mConnectedWifiClients = new ArrayList<>();
    private final List<TetheredClient> mTetheredClients = new ArrayList<>();
    private int mRefreshGeneration;
    private int mSelectedTetheringType = Integer.MIN_VALUE;
    @Nullable private String mSelectedMacAddress;
    @Nullable private String mSelectedHostname;
    private long mStartTimeMillis;
    private long mEndTimeMillis;
    private boolean mRollingLast24Hours;
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPeriodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshUsage();
            mRefreshHandler.postDelayed(this, 10_000L);
        }
    };
    private String mRangeLabel;
    private boolean mIsWifiCallbackRegistered;
    private boolean mIsTetheringCallbackRegistered;
    private boolean mIsClientManagementSupported = true;

    private final WifiManager.SoftApCallback mSoftApCallback =
            new WifiManager.SoftApCallback() {
                @Override
                public void onConnectedClientsChanged(@NonNull List<WifiClient> clients) {
                    mConnectedWifiClients.clear();
                    mConnectedWifiClients.addAll(clients);
                    refreshUsage();
                }

                @Override
                public void onCapabilityChanged(@NonNull SoftApCapability softApCapability) {
                    mIsClientManagementSupported =
                            softApCapability.areFeaturesSupported(
                                    SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT);
                    refreshUsage();
                }
            };

    private final TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onClientsChanged(Collection<TetheredClient> clients) {
                    mTetheredClients.clear();
                    mTetheredClients.addAll(clients);
                    refreshUsage();
                }
            };

    public static void launch(
            @NonNull Context context, int sourceMetricsCategory, @Nullable ClientRecord client) {
        Bundle args = new Bundle();
        int titleResId = R.string.wifi_tether_usage_details_title;
        if (client != null) {
            args.putInt(ARG_TETHERING_TYPE, client.getTetheringType());
            args.putString(ARG_MAC_ADDRESS, client.getMacAddress());
            args.putString(ARG_HOSTNAME, client.getHostname());
            titleResId = R.string.wifi_tether_usage_details_device_title;
        }
        new SubSettingLauncher(context)
                .setDestination(WifiTetherUsageDetailsSettings.class.getName())
                .setTitleRes(titleResId)
                .setArguments(args)
                .setSourceMetricsCategory(sourceMetricsCategory)
                .launch();
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.wifi_tether_usage_details;
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
        mClientRepository = WifiTetherClientRepository.getInstance(requireContext());
        mWifiManager = requireContext().getSystemService(WifiManager.class);
        mTetheringManager = requireContext().getSystemService(TetheringManager.class);
        mDeviceIdentityPreference = findPreference(KEY_DEVICE_IDENTITY);
        mTimeRangePreference = findPreference(KEY_TIME_RANGE);
        mSummaryCategory = findPreference(KEY_SUMMARY_CATEGORY);
        mActionsCategory = findPreference(KEY_ACTIONS_CATEGORY);
        mDevicesCategory = findPreference(KEY_DEVICES_CATEGORY);

        Bundle args = getArguments();
        if (args != null && args.containsKey(ARG_TETHERING_TYPE)) {
            mSelectedTetheringType = args.getInt(ARG_TETHERING_TYPE);
            mSelectedMacAddress = args.getString(ARG_MAC_ADDRESS);
            mSelectedHostname = args.getString(ARG_HOSTNAME);
        }

        selectLast24Hours();
        if (mTimeRangePreference != null) {
            mTimeRangePreference.setOnPreferenceClickListener(preference -> {
                showTimeRangeDialog();
                return true;
            });
        }
        refreshUsage();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerWifiCallback();
        registerTetheringCallback();
    }

    @Override
    public void onStop() {
        mRefreshGeneration++;
        mRefreshHandler.removeCallbacks(mPeriodicRefresh);
        unregisterWifiCallback();
        unregisterTetheringCallback();
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        mRefreshHandler.removeCallbacks(mPeriodicRefresh);
        mRefreshHandler.post(mPeriodicRefresh);
    }

    @Override
    public void onPause() {
        mRefreshHandler.removeCallbacks(mPeriodicRefresh);
        mRefreshGeneration++;
        super.onPause();
    }

    private void selectLast24Hours() {
        mRollingLast24Hours = true;
        mEndTimeMillis = System.currentTimeMillis();
        mStartTimeMillis = mEndTimeMillis - ONE_DAY_MILLIS;
        mRangeLabel = getString(R.string.wifi_tether_usage_details_last_24_hours);
        updateRangeSummary();
    }

    private void showTimeRangeDialog() {
        List<RangeChoice> choices = buildRangeChoices();
        CharSequence[] labels = choices.stream()
                .map(choice -> choice.label)
                .toArray(CharSequence[]::new);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wifi_tether_usage_details_time_range)
                .setItems(labels, (dialog, which) -> handleRangeChoice(choices.get(which)))
                .show();
    }

    private List<RangeChoice> buildRangeChoices() {
        long now = System.currentTimeMillis();
        long todayStart = dayStart(now);
        long yesterdayStart = addDays(todayStart, -1);
        List<RangeChoice> choices = new ArrayList<>();
        choices.add(RangeChoice.action(
                getString(R.string.wifi_tether_usage_details_last_24_hours),
                RangeChoice.ACTION_LAST_24_HOURS));
        choices.add(RangeChoice.range(
                getString(R.string.data_usage_today),
                todayStart,
                addDays(todayStart, 1)));
        choices.add(RangeChoice.range(
                getString(R.string.data_usage_yesterday),
                yesterdayStart,
                todayStart));

        Calendar month = Calendar.getInstance();
        month.setTimeInMillis(now);
        clearTime(month);
        month.set(Calendar.DAY_OF_MONTH, 1);
        for (int i = 0; i < 6; i++) {
            long start = month.getTimeInMillis();
            Calendar end = (Calendar) month.clone();
            end.add(Calendar.MONTH, 1);
            CharSequence label = i == 0
                    ? getString(R.string.wifi_tether_usage_details_this_month)
                    : DateUtils.formatDateTime(requireContext(), start,
                            DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE
                                    | DateUtils.FORMAT_NO_MONTH_DAY);
            choices.add(RangeChoice.range(label, start, end.getTimeInMillis()));
            month.add(Calendar.MONTH, -1);
        }
        choices.add(RangeChoice.action(
                getString(R.string.data_usage_select_date), RangeChoice.ACTION_PICK_DATE));
        choices.add(RangeChoice.action(
                getString(R.string.data_usage_select_range), RangeChoice.ACTION_PICK_RANGE));
        return choices;
    }

    private void handleRangeChoice(RangeChoice choice) {
        if (choice.action == RangeChoice.ACTION_LAST_24_HOURS) {
            selectLast24Hours();
            refreshUsage();
            return;
        }
        if (choice.action == RangeChoice.ACTION_PICK_DATE) {
            showDatePicker();
            return;
        }
        if (choice.action == RangeChoice.ACTION_PICK_RANGE) {
            showRangePicker();
            return;
        }
        mRollingLast24Hours = false;
        mStartTimeMillis = choice.startTimeMillis;
        mEndTimeMillis = choice.endTimeMillis;
        mRangeLabel = choice.label.toString();
        updateRangeSummary();
        refreshUsage();
    }

    private void showDatePicker() {
        showDatePickerDialog(
                getText(R.string.data_usage_select_date_title),
                dayStart(mStartTimeMillis > 0L ? mStartTimeMillis : System.currentTimeMillis()),
                startTime -> {
                    mRollingLast24Hours = false;
                    mStartTimeMillis = startTime;
                    mEndTimeMillis = addDays(startTime, 1);
                    mRangeLabel = getString(
                            R.string.data_usage_custom_date_template, formatDate(startTime));
                    updateRangeSummary();
                    refreshUsage();
                });
    }

    private void showRangePicker() {
        showDatePickerDialog(
                getText(R.string.data_usage_select_range_start_title),
                dayStart(mStartTimeMillis > 0L ? mStartTimeMillis : System.currentTimeMillis()),
                startTime -> showDatePickerDialog(
                        getText(R.string.data_usage_select_range_end_title),
                        mEndTimeMillis > mStartTimeMillis ? mEndTimeMillis - 1 : startTime,
                        endTime -> {
                            mRollingLast24Hours = false;
                            long lower = Math.min(startTime, endTime);
                            long upper = addDays(Math.max(startTime, endTime), 1);
                            mStartTimeMillis = lower;
                            mEndTimeMillis = upper;
                            mRangeLabel = getString(
                                    R.string.data_usage_custom_range_template,
                                    formatDateRange(lower, upper));
                            updateRangeSummary();
                            refreshUsage();
                        }));
    }

    private void showDatePickerDialog(
            CharSequence title, long initialTime, DateSelectedListener listener) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(initialTime);
        DatePickerDialog dialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) ->
                        listener.onDateSelected(dayStart(year, month, dayOfMonth)),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setTitle(title);
        dialog.show();
    }

    private void refreshUsage() {
        if (mSummaryCategory == null || mDevicesCategory == null) {
            return;
        }
        if (mRollingLast24Hours) {
            mEndTimeMillis = System.currentTimeMillis();
            mStartTimeMillis = mEndTimeMillis - ONE_DAY_MILLIS;
        }
        updateRangeSummary();
        final int generation = ++mRefreshGeneration;
        final long startTime = mStartTimeMillis;
        final long endTime = mEndTimeMillis;
        final List<TetheredClient> tetheredClients = new ArrayList<>(mTetheredClients);
        ThreadUtils.postOnBackgroundThread(() -> {
            mClientRepository.recordClients(tetheredClients);
            mClientRepository.refreshUsage();
            List<ClientRecord> clients = mClientRepository.getClients();
            Map<Integer, UsageBytes> usageByTag =
                    mClientRepository.queryTaggedUsage(startTime, endTime);
            List<DeviceUsage> rows = buildDeviceRows(clients, usageByTag, tetheredClients);
            UsageBytes totalUsage = sumDeviceUsage(rows);
            ThreadUtils.postOnMainThread(() -> {
                if (!isAdded() || generation != mRefreshGeneration) {
                    return;
                }
                bindUsage(totalUsage, rows);
            });
        });
    }

    private List<DeviceUsage> buildDeviceRows(
            List<ClientRecord> clients,
            Map<Integer, UsageBytes> usageByTag,
            List<TetheredClient> tetheredClients) {
        List<DeviceUsage> rows = new ArrayList<>();
        Set<Integer> clientTags = new HashSet<>();
        for (ClientRecord client : clients) {
            clientTags.add(client.getStatsTag());
        }
        for (ClientRecord client : clients) {
            UsageBytes usage = usageByTag.get(client.getStatsTag());
            if (usage == null) {
                usage = new UsageBytes();
            }
            if (isShowingSingleDevice() && !isSelectedClient(client)) {
                continue;
            }
            rows.add(new DeviceUsage(client, usage));
        }
        if (!isShowingSingleDevice()) {
            UsageBytes unclassifiedUsage = new UsageBytes();
            Map<TetheringUncategorizedReason, UsageBytes> unclassifiedReasons =
                    new EnumMap<>(TetheringUncategorizedReason.class);
            for (Map.Entry<Integer, UsageBytes> entry : usageByTag.entrySet()) {
                if (!clientTags.contains(entry.getKey())) {
                    unclassifiedUsage.add(entry.getValue());
                    addUnclassifiedReason(
                            unclassifiedReasons,
                            getUnclassifiedReason(entry.getKey(), tetheredClients),
                            entry.getValue());
                }
            }
            long reasonUsage = sumReasonUsage(unclassifiedReasons);
            if (unclassifiedUsage.getTotalBytes() > reasonUsage) {
                addUnclassifiedReason(
                        unclassifiedReasons,
                        TetheringUncategorizedReason.COUNTER_GAP,
                        new UsageBytes(unclassifiedUsage.getTotalBytes() - reasonUsage, 0L));
            }
            if (unclassifiedUsage.getTotalBytes() > 0L) {
                rows.add(new DeviceUsage(null, unclassifiedUsage, unclassifiedReasons));
            }
        }
        Collections.sort(rows, (first, second) -> {
            int usageCompare = Long.compare(
                    second.usage.getTotalBytes(), first.usage.getTotalBytes());
            if (usageCompare != 0) {
                return usageCompare;
            }
            if (first.client == null || second.client == null) {
                return first.client == null ? 1 : -1;
            }
            return Long.compare(second.client.getLastSeen(), first.client.getLastSeen());
        });
        return rows;
    }

    private void bindUsage(UsageBytes totalUsage, List<DeviceUsage> rows) {
        mSummaryCategory.removeAll();
        ClientRecord selectedClient =
                rows.stream().findFirst().map(row -> row.client).orElse(null);
        bindIdentityPreference(selectedClient);
        UsageBytes displayedTotal = isShowingSingleDevice()
                ? rows.stream().findFirst().map(row -> row.usage).orElse(new UsageBytes())
                : totalUsage;
        mSummaryCategory.setTitle(isShowingSingleDevice()
                ? R.string.wifi_tether_usage_details_device_usage_title
                : R.string.wifi_tether_usage_details_summary_title);
        addSimpleUsagePreference(
                mSummaryCategory,
                "wifi_tether_usage_total",
                getString(R.string.wifi_tether_usage_details_total),
                displayedTotal.getTotalBytes());
        addSimpleUsagePreference(
                mSummaryCategory,
                "wifi_tether_usage_upload",
                getString(R.string.wifi_tether_usage_details_upload),
                displayedTotal.getTxBytes());
        addSimpleUsagePreference(
                mSummaryCategory,
                "wifi_tether_usage_download",
                getString(R.string.wifi_tether_usage_details_download),
                displayedTotal.getRxBytes());
        bindActionsCategory(selectedClient);

        mDevicesCategory.removeAll();
        mDevicesCategory.setVisible(!isShowingSingleDevice());
        mDevicesCategory.setTitle(R.string.wifi_tether_usage_details_devices_title);
        if (isShowingSingleDevice()) {
            return;
        }
        if (rows.isEmpty()) {
            addEmptyPreference(mDevicesCategory, R.string.wifi_tether_usage_details_no_devices);
        } else {
            for (DeviceUsage row : rows) {
                if (row.client == null) {
                    addUnclassifiedPreference(mDevicesCategory, row);
                } else {
                    addUsagePreference(
                            mDevicesCategory,
                            getDevicePreferenceKey(row.client),
                            getClientLabel(row.client),
                            row.usage,
                            !isShowingSingleDevice(),
                            row.client);
                }
            }
        }
    }

    private UsageBytes sumDeviceUsage(List<DeviceUsage> rows) {
        UsageBytes totalUsage = new UsageBytes();
        for (DeviceUsage row : rows) {
            totalUsage.add(row.usage);
        }
        return totalUsage;
    }

    private static void addUnclassifiedReason(
            Map<TetheringUncategorizedReason, UsageBytes> reasons,
            TetheringUncategorizedReason reason,
            UsageBytes usage) {
        reasons.computeIfAbsent(reason, key -> new UsageBytes()).add(usage);
    }

    private static long sumReasonUsage(Map<TetheringUncategorizedReason, UsageBytes> reasons) {
        long totalBytes = 0L;
        for (UsageBytes usage : reasons.values()) {
            totalBytes += usage.getTotalBytes();
        }
        return totalBytes;
    }

    private TetheringUncategorizedReason getUnclassifiedReason(
            int tag, List<TetheredClient> tetheredClients) {
        if (tag != NetworkStats.Bucket.TAG_NONE) {
            return TetheringUncategorizedReason.UNMATCHED_CLIENT;
        }
        if (hasUnknownTetheringType(tetheredClients)) {
            return TetheringUncategorizedReason.UNKNOWN_IFACE;
        }
        if (hasClientWithoutIp(tetheredClients)) {
            return TetheringUncategorizedReason.NO_IP;
        }
        if (hasIpv6OnlyClient(tetheredClients)) {
            return TetheringUncategorizedReason.IPV6_UNMATCHED;
        }
        return TetheringUncategorizedReason.NON_CLIENT_TETHER;
    }

    private static boolean hasUnknownTetheringType(List<TetheredClient> tetheredClients) {
        for (TetheredClient client : tetheredClients) {
            if (!isKnownTetheringType(client.getTetheringType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownTetheringType(int tetheringType) {
        switch (WifiTetherClientRepository.normalizeTetheringType(tetheringType)) {
            case TetheringManager.TETHERING_WIFI:
            case TetheringManager.TETHERING_USB:
            case TetheringManager.TETHERING_BLUETOOTH:
            case TetheringManager.TETHERING_NCM:
            case TetheringManager.TETHERING_ETHERNET:
            case TetheringManager.TETHERING_VIRTUAL:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasClientWithoutIp(List<TetheredClient> tetheredClients) {
        for (TetheredClient client : tetheredClients) {
            if (client.getAddresses().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIpv6OnlyClient(List<TetheredClient> tetheredClients) {
        for (TetheredClient client : tetheredClients) {
            if (clientHasOnlyIpv6Addresses(client)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clientHasOnlyIpv6Addresses(TetheredClient client) {
        boolean hasIpv6Address = false;
        for (TetheredClient.AddressInfo addressInfo : client.getAddresses()) {
            Object address = addressInfo.getAddress().getAddress();
            if (address instanceof Inet4Address) {
                return false;
            }
            if (address instanceof Inet6Address) {
                hasIpv6Address = true;
            }
        }
        return hasIpv6Address;
    }

    private void addUsagePreference(
            PreferenceCategory category, String key, String title, UsageBytes usage,
            boolean selectable, @Nullable ClientRecord client) {
        Preference preference = new Preference(getPrefContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(formatUsage(usage));
        preference.setSelectable(selectable);
        if (selectable && client != null) {
            preference.setOnPreferenceClickListener(clicked -> {
                launch(requireContext(), getMetricsCategory(), client);
                return true;
            });
        }
        category.addPreference(preference);
    }

    private void addSimpleUsagePreference(
            PreferenceCategory category, String key, String title, long bytes) {
        Preference preference = new Preference(getPrefContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(Formatter.formatFileSize(requireContext(), bytes));
        preference.setSelectable(false);
        category.addPreference(preference);
    }

    private void addUnclassifiedPreference(PreferenceCategory category, DeviceUsage row) {
        UsageBytes usage = row.usage;
        logUnclassifiedUsage(row);
        Preference preference = new Preference(getPrefContext());
        preference.setKey("wifi_tether_usage_device_unclassified");
        preference.setTitle(R.string.wifi_tether_usage_details_uncategorized);
        preference.setSummary(getString(
                R.string.wifi_tether_usage_details_uncategorized_summary,
                Formatter.formatFileSize(requireContext(), usage.getTotalBytes())));
        preference.setSelectable(false);
        category.addPreference(preference);
    }

    private void logUnclassifiedUsage(DeviceUsage row) {
        if (row.usage.getTotalBytes() <= 0L || row.unclassifiedReasons.isEmpty()) {
            return;
        }
        StringBuilder reasons = new StringBuilder();
        for (Map.Entry<TetheringUncategorizedReason, UsageBytes> entry
                : row.unclassifiedReasons.entrySet()) {
            if (reasons.length() > 0) {
                reasons.append(", ");
            }
            UsageBytes usage = entry.getValue();
            reasons.append(entry.getKey())
                    .append("=bytes:")
                    .append(usage.getTotalBytes())
                    .append(",rx:")
                    .append(usage.getRxBytes())
                    .append(",tx:")
                    .append(usage.getTxBytes());
        }
        Log.d(
                TAG,
                "Uncategorized tethering usage total="
                        + row.usage.getTotalBytes()
                        + ", reasons={"
                        + reasons
                        + "}");
    }

    private String formatUsage(UsageBytes usage) {
        return getString(
                R.string.wifi_tether_usage_details_usage_split,
                Formatter.formatFileSize(requireContext(), usage.getTotalBytes()),
                Formatter.formatFileSize(requireContext(), usage.getTxBytes()),
                Formatter.formatFileSize(requireContext(), usage.getRxBytes()));
    }

    private void bindIdentityPreference(@Nullable ClientRecord selectedClient) {
        if (mDeviceIdentityPreference == null) {
            return;
        }
        mDeviceIdentityPreference.setVisible(isShowingSingleDevice());
        if (!isShowingSingleDevice()) {
            return;
        }
        String hostname = selectedClient != null ? selectedClient.getHostname() : mSelectedHostname;
        String macAddress =
                selectedClient != null ? selectedClient.getMacAddress() : mSelectedMacAddress;
        mDeviceIdentityPreference.setTitle(TextUtils.isEmpty(hostname)
                ? getString(R.string.wifi_tether_usage_unknown_hostname)
                : hostname);
        mDeviceIdentityPreference.setSummary(
                TextUtils.isEmpty(macAddress)
                        ? getString(R.string.wifi_tether_hotspot_details_unavailable)
                        : getString(R.string.wifi_tether_usage_device_mac_address, macAddress));
    }

    private void bindActionsCategory(@Nullable ClientRecord selectedClient) {
        if (mActionsCategory == null) {
            return;
        }
        mActionsCategory.removeAll();
        MacAddress macAddress = getSelectedMacAddress();
        boolean showActions = isShowingSingleDevice()
                && macAddress != null
                && WifiTetherClientRepository.normalizeTetheringType(mSelectedTetheringType)
                        == TetheringManager.TETHERING_WIFI;
        mActionsCategory.setVisible(showActions);
        if (!showActions) {
            return;
        }
        addActionPreference(
                mActionsCategory,
                "wifi_tether_usage_action_block",
                R.string.wifi_tether_client_block,
                mIsClientManagementSupported,
                () -> showBlockClientDialog(macAddress, getSelectedClientLabel(selectedClient)));
        if (isSelectedWifiConnected(macAddress)) {
            addActionPreference(
                    mActionsCategory,
                    "wifi_tether_usage_action_disconnect",
                    R.string.wifi_tether_client_disconnect,
                    mIsClientManagementSupported,
                    () -> disconnectClient(macAddress));
        }
    }

    private void addActionPreference(PreferenceCategory category, String key, int titleResId,
            boolean enabled, Runnable action) {
        Preference preference = new Preference(getPrefContext());
        preference.setKey(key);
        preference.setTitle(titleResId);
        preference.setEnabled(enabled);
        if (!enabled) {
            preference.setSummary(R.string.wifi_tether_feature_unavailable);
        }
        preference.setOnPreferenceClickListener(clicked -> {
            action.run();
            return true;
        });
        category.addPreference(preference);
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
            refreshUsage();
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Failed to update blocked client list", e);
        }
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
        refreshUsage();
    }

    private void registerWifiCallback() {
        if (mWifiManager == null || mIsWifiCallbackRegistered) {
            return;
        }
        try {
            mWifiManager.registerSoftApCallback(
                    requireContext().getMainExecutor(), mSoftApCallback);
            mIsWifiCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to register Soft AP callback", e);
        }
    }

    private void unregisterWifiCallback() {
        if (mWifiManager == null || !mIsWifiCallbackRegistered) {
            return;
        }
        try {
            mWifiManager.unregisterSoftApCallback(mSoftApCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to unregister Soft AP callback", e);
        }
        mIsWifiCallbackRegistered = false;
    }

    private void registerTetheringCallback() {
        if (mTetheringManager == null || mIsTetheringCallbackRegistered) {
            return;
        }
        try {
            mTetheringManager.registerTetheringEventCallback(
                    requireContext().getMainExecutor(), mTetheringCallback);
            mIsTetheringCallbackRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to register tethering callback", e);
        }
    }

    private void unregisterTetheringCallback() {
        if (mTetheringManager == null || !mIsTetheringCallbackRegistered) {
            return;
        }
        try {
            mTetheringManager.unregisterTetheringEventCallback(mTetheringCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to unregister tethering callback", e);
        }
        mIsTetheringCallbackRegistered = false;
    }

    private boolean isSelectedWifiConnected(MacAddress macAddress) {
        for (WifiClient client : mConnectedWifiClients) {
            if (macAddress.equals(client.getMacAddress())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private MacAddress getSelectedMacAddress() {
        if (TextUtils.isEmpty(mSelectedMacAddress)) {
            return null;
        }
        try {
            return MacAddress.fromString(mSelectedMacAddress);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid selected hotspot client MAC: " + mSelectedMacAddress, e);
            return null;
        }
    }

    private String getSelectedClientLabel(@Nullable ClientRecord selectedClient) {
        if (selectedClient != null) {
            return getClientLabel(selectedClient);
        }
        return TextUtils.isEmpty(mSelectedHostname)
                ? mSelectedMacAddress
                : getString(
                        R.string.data_usage_tethering_client_label,
                        mSelectedHostname,
                        mSelectedMacAddress);
    }

    private void updateRangeSummary() {
        if (mTimeRangePreference != null && !TextUtils.isEmpty(mRangeLabel)) {
            mTimeRangePreference.setSummary(mRangeLabel);
        }
    }

    private boolean isShowingSingleDevice() {
        return mSelectedTetheringType != Integer.MIN_VALUE
                && !TextUtils.isEmpty(mSelectedMacAddress);
    }

    private boolean isSelectedClient(ClientRecord client) {
        return WifiTetherClientRepository.normalizeTetheringType(client.getTetheringType())
                        == WifiTetherClientRepository.normalizeTetheringType(mSelectedTetheringType)
                && TextUtils.equals(client.getMacAddress(), mSelectedMacAddress);
    }

    private String getDevicePreferenceKey(ClientRecord client) {
        return String.format(
                Locale.US,
                "wifi_tether_usage_device_%d_%s",
                WifiTetherClientRepository.normalizeTetheringType(client.getTetheringType()),
                client.getMacAddress());
    }

    private String getClientLabel(ClientRecord client) {
        String hostname = client.getHostname();
        if (isSelectedClient(client) && TextUtils.isEmpty(hostname)) {
            hostname = mSelectedHostname;
        }
        String clientLabel = TextUtils.isEmpty(hostname)
                ? client.getMacAddress()
                : getString(
                        R.string.data_usage_tethering_client_label,
                        hostname,
                        client.getMacAddress());
        int type = WifiTetherClientRepository.normalizeTetheringType(client.getTetheringType());
        if (type == TetheringManager.TETHERING_WIFI) {
            return clientLabel;
        }
        return getString(
                R.string.data_usage_tethering_typed_client_label,
                getTetheringTypeLabel(type),
                clientLabel);
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

    private void addEmptyPreference(PreferenceCategory category, int titleResId) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(titleResId);
        preference.setSelectable(false);
        category.addPreference(preference);
    }

    private long dayStart(long timeMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMillis);
        clearTime(calendar);
        return calendar.getTimeInMillis();
    }

    private long dayStart(int year, int month, int dayOfMonth) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, dayOfMonth, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long addDays(long timeMillis, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMillis);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTimeInMillis();
    }

    private static void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private String formatDate(long timeMillis) {
        return DateUtils.formatDateTime(requireContext(), timeMillis, DATE_FORMAT);
    }

    private String formatDateRange(long startTimeMillis, long endTimeMillis) {
        return DateUtils.formatDateRange(
                requireContext(), startTimeMillis, endTimeMillis - 1, DATE_FORMAT);
    }

    private interface DateSelectedListener {
        void onDateSelected(long startTimeMillis);
    }

    private static final class DeviceUsage {
        @Nullable
        final ClientRecord client;
        final UsageBytes usage;
        final Map<TetheringUncategorizedReason, UsageBytes> unclassifiedReasons;

        DeviceUsage(ClientRecord client, UsageBytes usage) {
            this(client, usage, Collections.emptyMap());
        }

        DeviceUsage(
                ClientRecord client,
                UsageBytes usage,
                Map<TetheringUncategorizedReason, UsageBytes> unclassifiedReasons) {
            this.client = client;
            this.usage = usage;
            this.unclassifiedReasons = unclassifiedReasons;
        }
    }

    private static final class RangeChoice {
        static final int ACTION_RANGE = 0;
        static final int ACTION_PICK_DATE = 1;
        static final int ACTION_PICK_RANGE = 2;
        static final int ACTION_LAST_24_HOURS = 3;

        final CharSequence label;
        final long startTimeMillis;
        final long endTimeMillis;
        final int action;

        private RangeChoice(CharSequence label, long startTimeMillis, long endTimeMillis,
                int action) {
            this.label = label;
            this.startTimeMillis = startTimeMillis;
            this.endTimeMillis = endTimeMillis;
            this.action = action;
        }

        static RangeChoice range(CharSequence label, long startTimeMillis, long endTimeMillis) {
            return new RangeChoice(label, startTimeMillis, endTimeMillis, ACTION_RANGE);
        }

        static RangeChoice action(CharSequence label, int action) {
            return new RangeChoice(label, 0L, 0L, action);
        }
    }
}
