/*
 * Copyright (C) 2020 The LineageOS Project
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

package com.android.settings.display;

import static android.provider.Settings.System.MIN_REFRESH_RATE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MinRefreshRatePreferenceController extends BasePreferenceController
        implements LifecycleObserver, OnStart, OnStop, Preference.OnPreferenceChangeListener {

    private static final String TAG = "MinRefreshRatePrefCtr";
    private static final String KEY_MIN_REFRESH_RATE = "min_refresh_rate";

    private final ContentObserver mRefreshRateObserver;
    private ListPreference mListPreference;

    private List<String> mEntries = new ArrayList<>();
    private List<String> mValues = new ArrayList<>();

    public MinRefreshRatePreferenceController(Context context) {
        super(context, KEY_MIN_REFRESH_RATE);
        mRefreshRateObserver = new ContentObserver(new Handler(context.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateState(mListPreference);
            }
        };

        if (mContext.getResources().getBoolean(R.bool.config_show_min_refresh_rate_switch)) {
            final DisplayManager displayManager =
                    mContext.getSystemService(DisplayManager.class);
            final Display display = displayManager != null
                    ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) {
                Log.w(TAG, "No valid default display device");
                return;
            }
            Display.Mode mode = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            Arrays.sort(modes, (mode1, mode2) ->
                Float.compare(mode2.getRefreshRate(), mode1.getRefreshRate()));
            for (Display.Mode m : modes) {
                if (m.getPhysicalWidth() == mode.getPhysicalWidth() &&
                        m.getPhysicalHeight() == mode.getPhysicalHeight()) {
                    mEntries.add(String.format("%.02fHz", m.getRefreshRate())
                            .replaceAll("[\\.,]00", ""));
                    mValues.add(String.format(Locale.US, "%.02f", m.getRefreshRate()));
                }
            }
        }
    }

    @Override
    public int getAvailabilityStatus() {
        return mEntries.size() > 1 ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_MIN_REFRESH_RATE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        mListPreference = screen.findPreference(getPreferenceKey());
        mListPreference.setEntries(mEntries.toArray(new String[mEntries.size()]));
        mListPreference.setEntryValues(mValues.toArray(new String[mValues.size()]));

        super.displayPreference(screen);
    }

    @Override
    public void updateState(Preference preference) {
        if (mListPreference == null || mEntries.isEmpty()) {
            return;
        }
        final float currentValue = Settings.System.getFloat(mContext.getContentResolver(),
                MIN_REFRESH_RATE, 60.00f);
        int index = mListPreference.findIndexOfValue(
                String.format(Locale.US, "%.02f", currentValue));
        if (Float.isInfinite(currentValue)) {
            index = 0;
        } else if (index < 0) {
            index = mEntries.size() - 1;
        }
        mListPreference.setValueIndex(index);
        mListPreference.setSummary(mListPreference.getEntries()[index]);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final ContentResolver resolver = mContext.getContentResolver();
        final float minRefreshRate = Float.parseFloat((String) newValue);
        final float peakRefreshRate = Settings.System.getFloat(resolver,
                Settings.System.PEAK_REFRESH_RATE, getDefaultPeakRefreshRate());
        // A zero peak means there is no user-set upper bound.
        if (peakRefreshRate != 0f && minRefreshRate > peakRefreshRate) {
            // Keep the user's new lower bound valid without requiring a second menu change.
            Settings.System.putFloat(resolver, Settings.System.PEAK_REFRESH_RATE, minRefreshRate);
        }
        Settings.System.putFloat(resolver, MIN_REFRESH_RATE, minRefreshRate);
        updateState(preference);
        return true;
    }

    @Override
    public void onStart() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(MIN_REFRESH_RATE), false, mRefreshRateObserver);
        updateState(mListPreference);
    }

    @Override
    public void onStop() {
        mContext.getContentResolver().unregisterContentObserver(mRefreshRateObserver);
    }

    private float getDefaultPeakRefreshRate() {
        final float configuredDefault = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultPeakRefreshRate);
        final float deviceConfigDefault = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT, -1f);
        return deviceConfigDefault == -1f ? configuredDefault : deviceConfigDefault;
    }

}
