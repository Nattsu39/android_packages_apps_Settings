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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Stores identities and cached usage for devices which have used tethering. */
final class WifiTetherClientDatabase extends SQLiteOpenHelper {

    static final String TABLE_CLIENTS = "tethering_clients";
    static final String COLUMN_ID = "_id";
    static final String COLUMN_TETHERING_TYPE = "tethering_type";
    static final String COLUMN_MAC_ADDRESS = "mac_address";
    static final String COLUMN_HOSTNAME = "hostname";
    static final String COLUMN_STATS_TAG = "stats_tag";
    static final String COLUMN_FIRST_SEEN = "first_seen";
    static final String COLUMN_LAST_SEEN = "last_seen";
    static final String COLUMN_USAGE_BYTES = "usage_bytes";
    static final String COLUMN_STATS_SNAPSHOT_BYTES = "stats_snapshot_bytes";

    private static final String LEGACY_TABLE_USAGE_HISTORY = "tethering_client_usage_history";
    private static final String LEGACY_TABLE_METADATA = "tethering_metadata";
    private static final String LEGACY_COLUMN_RX_BYTES = "rx_bytes";
    private static final String LEGACY_COLUMN_TX_BYTES = "tx_bytes";

    private static final String DATABASE_NAME = "tethering_clients.db";
    private static final int DATABASE_VERSION = 4;

    private static WifiTetherClientDatabase sInstance;

    static synchronized WifiTetherClientDatabase getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WifiTetherClientDatabase(context.getApplicationContext());
        }
        return sInstance;
    }

    private WifiTetherClientDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE "
                        + TABLE_CLIENTS
                        + " ("
                        + COLUMN_ID
                        + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_TETHERING_TYPE
                        + " INTEGER NOT NULL, "
                        + COLUMN_MAC_ADDRESS
                        + " TEXT NOT NULL, "
                        + COLUMN_HOSTNAME
                        + " TEXT, "
                        + COLUMN_STATS_TAG
                        + " INTEGER NOT NULL, "
                        + COLUMN_FIRST_SEEN
                        + " INTEGER NOT NULL, "
                        + COLUMN_LAST_SEEN
                        + " INTEGER NOT NULL, "
                        + COLUMN_USAGE_BYTES
                        + " INTEGER NOT NULL DEFAULT 0, "
                        + COLUMN_STATS_SNAPSHOT_BYTES
                        + " INTEGER NOT NULL DEFAULT 0, "
                        + "UNIQUE("
                        + COLUMN_TETHERING_TYPE
                        + ", "
                        + COLUMN_MAC_ADDRESS
                        + "))");
        db.execSQL(
                "CREATE INDEX tethering_clients_stats_tag_index ON "
                        + TABLE_CLIENTS
                        + " ("
                        + COLUMN_STATS_TAG
                        + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2
                && !hasColumn(db, TABLE_CLIENTS, COLUMN_STATS_SNAPSHOT_BYTES)) {
            db.execSQL(
                    "ALTER TABLE "
                            + TABLE_CLIENTS
                            + " ADD COLUMN "
                            + COLUMN_STATS_SNAPSHOT_BYTES
                            + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL(
                    "UPDATE "
                            + TABLE_CLIENTS
                            + " SET "
                            + COLUMN_STATS_SNAPSHOT_BYTES
                            + " = "
                            + COLUMN_USAGE_BYTES);
        }
        if (oldVersion < 4) {
            removeLegacyFallbackAttribution(db);
            db.execSQL("DROP TABLE IF EXISTS " + LEGACY_TABLE_USAGE_HISTORY);
            db.execSQL("DROP TABLE IF EXISTS " + LEGACY_TABLE_METADATA);
        }
    }

    private static void removeLegacyFallbackAttribution(SQLiteDatabase db) {
        if (!hasTable(db, LEGACY_TABLE_USAGE_HISTORY)) {
            return;
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT "
                                + COLUMN_TETHERING_TYPE
                                + ", "
                                + COLUMN_MAC_ADDRESS
                                + ", SUM("
                                + LEGACY_COLUMN_RX_BYTES
                                + " + "
                                + LEGACY_COLUMN_TX_BYTES
                                + ") FROM "
                                + LEGACY_TABLE_USAGE_HISTORY
                                + " GROUP BY "
                                + COLUMN_TETHERING_TYPE
                                + ", "
                                + COLUMN_MAC_ADDRESS,
                        null)) {
            while (cursor.moveToNext()) {
                long fallbackBytes = cursor.getLong(2);
                if (fallbackBytes <= 0L) {
                    continue;
                }
                db.execSQL(
                        "UPDATE "
                                + TABLE_CLIENTS
                                + " SET "
                                + COLUMN_USAGE_BYTES
                                + " = CASE WHEN "
                                + COLUMN_USAGE_BYTES
                                + " > ? THEN "
                                + COLUMN_USAGE_BYTES
                                + " - ? ELSE 0 END WHERE "
                                + COLUMN_TETHERING_TYPE
                                + " = ? AND "
                                + COLUMN_MAC_ADDRESS
                                + " = ?",
                        new Object[] {
                            fallbackBytes, fallbackBytes, cursor.getInt(0), cursor.getString(1)
                        });
            }
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String tableName) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        new String[] {tableName})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String tableName, String columnName) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (columnName.equals(cursor.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }
}
