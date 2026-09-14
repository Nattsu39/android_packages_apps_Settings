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
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.MacAddress;
import android.net.NetworkTemplate;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.settings.datausage.DataUsageUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Persistent device identities and all-time usage for tethering clients. */
public final class WifiTetherClientRepository {

    private static final String TAG = "WifiTetherClientRepo";
    private static WifiTetherClientRepository sInstance;

    private final Context mContext;
    private final WifiTetherClientDatabase mDatabase;

    public static synchronized WifiTetherClientRepository getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WifiTetherClientRepository(context.getApplicationContext());
        }
        return sInstance;
    }

    private WifiTetherClientRepository(Context context) {
        mContext = context;
        mDatabase = WifiTetherClientDatabase.getInstance(context);
    }

    public synchronized void recordClients(Collection<TetheredClient> clients) {
        if (clients == null || clients.isEmpty()) {
            return;
        }
        SQLiteDatabase db = mDatabase.getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            for (TetheredClient client : clients) {
                String hostname =
                        client.getAddresses().stream()
                                .map(TetheredClient.AddressInfo::getHostname)
                                .filter(name -> !TextUtils.isEmpty(name))
                                .findFirst()
                                .orElse(null);
                upsertClient(
                        db,
                        client.getTetheringType(),
                        client.getMacAddress(),
                        hostname,
                        makeStatsTag(client.getMacAddress(), client.getTetheringType()),
                        now);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void recordWifiClient(MacAddress macAddress) {
        if (macAddress == null) {
            return;
        }
        SQLiteDatabase db = mDatabase.getWritableDatabase();
        upsertClient(
                db,
                TetheringManager.TETHERING_WIFI,
                macAddress,
                null,
                makeStatsTag(macAddress, TetheringManager.TETHERING_WIFI),
                System.currentTimeMillis());
    }

    public synchronized List<ClientRecord> getClients() {
        SQLiteDatabase db = mDatabase.getReadableDatabase();
        List<ClientRecord> clients = new ArrayList<>();
        try (Cursor cursor =
                db.query(
                        WifiTetherClientDatabase.TABLE_CLIENTS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        WifiTetherClientDatabase.COLUMN_LAST_SEEN + " DESC")) {
            int typeIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_TETHERING_TYPE);
            int macIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_MAC_ADDRESS);
            int hostnameIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_HOSTNAME);
            int tagIndex = cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_STATS_TAG);
            int firstSeenIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_FIRST_SEEN);
            int lastSeenIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_LAST_SEEN);
            int usageIndex =
                    cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_USAGE_BYTES);
            while (cursor.moveToNext()) {
                clients.add(
                        new ClientRecord(
                                cursor.getInt(typeIndex),
                                cursor.getString(macIndex),
                                cursor.getString(hostnameIndex),
                                cursor.getInt(tagIndex),
                                cursor.getLong(firstSeenIndex),
                                cursor.getLong(lastSeenIndex),
                                cursor.getLong(usageIndex)));
            }
        }
        return clients;
    }

    /** Adds NetworkStats deltas to the persistent all-time usage counters. */
    public synchronized Map<Integer, Long> refreshUsage() {
        Map<Integer, UsageBytes> allUsageByTag =
                queryTaggedUsage(0L, System.currentTimeMillis());
        Map<Integer, Long> usageByTag = new HashMap<>();
        for (Map.Entry<Integer, UsageBytes> entry : allUsageByTag.entrySet()) {
            usageByTag.put(entry.getKey(), entry.getValue().getTotalBytes());
        }
        SQLiteDatabase db = mDatabase.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor =
                    db.query(
                            WifiTetherClientDatabase.TABLE_CLIENTS,
                            new String[] {
                                WifiTetherClientDatabase.COLUMN_ID,
                                WifiTetherClientDatabase.COLUMN_STATS_TAG,
                                WifiTetherClientDatabase.COLUMN_USAGE_BYTES,
                                WifiTetherClientDatabase.COLUMN_STATS_SNAPSHOT_BYTES,
                            },
                            null,
                            null,
                            null,
                            null,
                            null)) {
                int idIndex = cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_ID);
                int tagIndex =
                        cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_STATS_TAG);
                int usageIndex =
                        cursor.getColumnIndexOrThrow(WifiTetherClientDatabase.COLUMN_USAGE_BYTES);
                int snapshotIndex =
                        cursor.getColumnIndexOrThrow(
                                WifiTetherClientDatabase.COLUMN_STATS_SNAPSHOT_BYTES);
                while (cursor.moveToNext()) {
                    int statsTag = cursor.getInt(tagIndex);
                    Long currentStatsValue = usageByTag.get(statsTag);
                    if (currentStatsValue == null) {
                        continue;
                    }
                    long currentStats = currentStatsValue;
                    long previousStats = cursor.getLong(snapshotIndex);
                    long persistedUsage = cursor.getLong(usageIndex);
                    long delta =
                            currentStats >= previousStats
                                    ? currentStats - previousStats
                                    : currentStats;

                    ContentValues values = new ContentValues();
                    values.put(WifiTetherClientDatabase.COLUMN_USAGE_BYTES, persistedUsage + delta);
                    values.put(WifiTetherClientDatabase.COLUMN_STATS_SNAPSHOT_BYTES, currentStats);
                    db.update(
                            WifiTetherClientDatabase.TABLE_CLIENTS,
                            values,
                            WifiTetherClientDatabase.COLUMN_ID + "=?",
                            new String[] {String.valueOf(cursor.getLong(idIndex))});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return usageByTag;
    }

    /**
     * Returns all tethering usage grouped by endpoint tag.
     *
     * <p>The tethering module emits these tagged {@code UID_TETHERING} entries from its
     * client-stats BPF map, keyed by upstream interface, downstream interface and client MAC.
     * That makes NAT/re-shared IPv4 traffic and native IPv6 forwarding follow the tethered MAC
     * when the kernel path can report a per-client tag.
     *
     * <p>{@link NetworkStats.Bucket#TAG_NONE} from {@link NetworkStatsManager#querySummary} is the
     * canonical total UID_TETHERING counter, not a separate untagged-only bucket. Return only the
     * remainder of {@code total - tagged clients} under {@link NetworkStats.Bucket#TAG_NONE} so the
     * device details UI can show unclassified traffic without double-counting tagged clients.
     */
    public Map<Integer, UsageBytes> queryTaggedUsage(long startTime, long endTime) {
        NetworkStatsManager statsManager = mContext.getSystemService(NetworkStatsManager.class);
        if (statsManager == null || startTime >= endTime) {
            return Collections.emptyMap();
        }
        Map<Integer, UsageBytes> usageByTag = new HashMap<>();
        UsageBytes totalUsage = new UsageBytes();
        for (NetworkTemplate template : buildUpstreamTemplates()) {
            try (NetworkStats stats =
                    statsManager.queryTaggedSummary(template, startTime, endTime)) {
                addTaggedTetheringBuckets(usageByTag, stats);
            } catch (Exception e) {
                Log.e(TAG, "Unable to query tagged tethering usage", e);
                // Never persist a partial snapshot: a later successful query would count
                // the missing upstream's historical bytes again as newly used traffic.
                return Collections.emptyMap();
            }
            try (NetworkStats stats = statsManager.querySummary(template, startTime, endTime)) {
                totalUsage.add(sumTetheringBuckets(stats));
            } catch (Exception e) {
                Log.e(TAG, "Unable to query tethering total usage", e);
                return Collections.emptyMap();
            }
        }
        UsageBytes taggedUsage = sumUsage(usageByTag.values());
        UsageBytes unclassifiedUsage = subtract(totalUsage, taggedUsage);
        if (unclassifiedUsage.getTotalBytes() > 0L) {
            usageByTag.put(NetworkStats.Bucket.TAG_NONE, unclassifiedUsage);
        }
        return usageByTag;
    }

    private static void addTaggedTetheringBuckets(
            Map<Integer, UsageBytes> usageByTag, NetworkStats stats) {
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket);
            if (bucket.getUid() != NetworkStats.Bucket.UID_TETHERING
                    || bucket.getTag() == NetworkStats.Bucket.TAG_NONE) {
                continue;
            }
            usageByTag
                    .computeIfAbsent(bucket.getTag(), tag -> new UsageBytes())
                    .add(bucket.getRxBytes(), bucket.getTxBytes());
        }
    }

    private static UsageBytes sumTetheringBuckets(NetworkStats stats) {
        UsageBytes usage = new UsageBytes();
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

    private static UsageBytes sumUsage(Collection<UsageBytes> usages) {
        UsageBytes totalUsage = new UsageBytes();
        for (UsageBytes usage : usages) {
            totalUsage.add(usage);
        }
        return totalUsage;
    }

    private static UsageBytes subtract(UsageBytes totalUsage, UsageBytes attributedUsage) {
        return new UsageBytes(
                totalUsage.getRxBytes() - attributedUsage.getRxBytes(),
                totalUsage.getTxBytes() - attributedUsage.getTxBytes());
    }

    public synchronized Set<Integer> getStatsTagsForType(int tetheringType) {
        int normalizedType = normalizeTetheringType(tetheringType);
        return getClients().stream()
                .filter(
                        client ->
                                normalizeTetheringType(client.getTetheringType()) == normalizedType)
                .map(ClientRecord::getStatsTag)
                .collect(Collectors.toSet());
    }

    public synchronized ClientRecord findClient(int tetheringType, String macAddress) {
        int normalizedType = normalizeTetheringType(tetheringType);
        return getClients().stream()
                .filter(
                        client ->
                                normalizeTetheringType(client.getTetheringType()) == normalizedType)
                .filter(client -> TextUtils.equals(client.getMacAddress(), macAddress))
                .findFirst()
                .orElse(null);
    }

    public static int normalizeTetheringType(int type) {
        return type == TetheringManager.TETHERING_WIFI_P2P ? TetheringManager.TETHERING_WIFI : type;
    }

    /** Mirrors hidden TetheredClient.makeStatsTag for Settings-side lookups. */
    public static int makeStatsTag(MacAddress macAddress, int tetheringType) {
        int hash = 0x811c9dc5;
        byte[] bytes = macAddress.toByteArray();
        for (byte b : bytes) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        hash ^= tetheringType & 0xff;
        hash *= 0x01000193;
        return 0x40000000 | (hash & 0x3fffffff);
    }

    private void upsertClient(
            SQLiteDatabase db,
            int type,
            MacAddress macAddress,
            String hostname,
            int statsTag,
            long now) {
        String mac = macAddress.toString();
        ContentValues initial = new ContentValues();
        initial.put(WifiTetherClientDatabase.COLUMN_TETHERING_TYPE, type);
        initial.put(WifiTetherClientDatabase.COLUMN_MAC_ADDRESS, mac);
        initial.put(WifiTetherClientDatabase.COLUMN_STATS_TAG, statsTag);
        initial.put(WifiTetherClientDatabase.COLUMN_FIRST_SEEN, now);
        initial.put(WifiTetherClientDatabase.COLUMN_LAST_SEEN, now);
        initial.put(WifiTetherClientDatabase.COLUMN_USAGE_BYTES, 0L);
        initial.put(WifiTetherClientDatabase.COLUMN_STATS_SNAPSHOT_BYTES, 0L);
        if (!TextUtils.isEmpty(hostname)) {
            initial.put(WifiTetherClientDatabase.COLUMN_HOSTNAME, hostname);
        }
        db.insertWithOnConflict(
                WifiTetherClientDatabase.TABLE_CLIENTS,
                null,
                initial,
                SQLiteDatabase.CONFLICT_IGNORE);

        ContentValues update = new ContentValues();
        update.put(WifiTetherClientDatabase.COLUMN_STATS_TAG, statsTag);
        update.put(WifiTetherClientDatabase.COLUMN_LAST_SEEN, now);
        if (!TextUtils.isEmpty(hostname)) {
            update.put(WifiTetherClientDatabase.COLUMN_HOSTNAME, hostname);
        }
        db.update(
                WifiTetherClientDatabase.TABLE_CLIENTS,
                update,
                WifiTetherClientDatabase.COLUMN_TETHERING_TYPE
                        + "=? AND "
                        + WifiTetherClientDatabase.COLUMN_MAC_ADDRESS
                        + "=?",
                new String[] {String.valueOf(type), mac});
    }

    private List<NetworkTemplate> buildUpstreamTemplates() {
        List<NetworkTemplate> templates = new ArrayList<>();
        if (DataUsageUtils.hasMobileData(mContext)) {
            templates.add(buildTemplate(NetworkTemplate.MATCH_MOBILE));
        }
        if (DataUsageUtils.hasWifiRadio(mContext)) {
            templates.add(buildTemplate(NetworkTemplate.MATCH_WIFI));
        }
        templates.add(buildTemplate(NetworkTemplate.MATCH_BLUETOOTH));
        templates.add(buildTemplate(NetworkTemplate.MATCH_ETHERNET));
        return templates;
    }

    private static NetworkTemplate buildTemplate(int matchRule) {
        return new NetworkTemplate.Builder(matchRule).build();
    }

    /** One persisted tethering client. */
    public static final class ClientRecord {
        private final int mTetheringType;
        private final String mMacAddress;
        private final String mHostname;
        private final int mStatsTag;
        private final long mFirstSeen;
        private final long mLastSeen;
        private final long mUsageBytes;

        ClientRecord(
                int tetheringType,
                String macAddress,
                String hostname,
                int statsTag,
                long firstSeen,
                long lastSeen,
                long usageBytes) {
            mTetheringType = tetheringType;
            mMacAddress = macAddress;
            mHostname = hostname;
            mStatsTag = statsTag;
            mFirstSeen = firstSeen;
            mLastSeen = lastSeen;
            mUsageBytes = usageBytes;
        }

        public int getTetheringType() {
            return mTetheringType;
        }

        public String getMacAddress() {
            return mMacAddress;
        }

        public String getHostname() {
            return mHostname;
        }

        public int getStatsTag() {
            return mStatsTag;
        }

        public long getFirstSeen() {
            return mFirstSeen;
        }

        public long getLastSeen() {
            return mLastSeen;
        }

        public long getUsageBytes() {
            return mUsageBytes;
        }
    }

    /** Upload/download byte counters for a tethered endpoint. */
    public static final class UsageBytes {
        private long mRxBytes;
        private long mTxBytes;

        public UsageBytes() {}

        public UsageBytes(long rxBytes, long txBytes) {
            mRxBytes = Math.max(0L, rxBytes);
            mTxBytes = Math.max(0L, txBytes);
        }

        public void add(long rxBytes, long txBytes) {
            mRxBytes += Math.max(0L, rxBytes);
            mTxBytes += Math.max(0L, txBytes);
        }

        public void add(UsageBytes usageBytes) {
            if (usageBytes == null) {
                return;
            }
            add(usageBytes.getRxBytes(), usageBytes.getTxBytes());
        }

        public long getRxBytes() {
            return mRxBytes;
        }

        public long getTxBytes() {
            return mTxBytes;
        }

        public long getTotalBytes() {
            return mRxBytes + mTxBytes;
        }
    }
}
