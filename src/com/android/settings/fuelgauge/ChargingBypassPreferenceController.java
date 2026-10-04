/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.fuelgauge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import lineageos.health.HealthInterface;

/** Displays acknowledged service state rather than a persisted switch value. */
public class ChargingBypassPreferenceController extends BasePreferenceController
        implements Preference.OnPreferenceChangeListener, LifecycleObserver, OnStart, OnStop {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final HealthInterface mHealth;
    private SwitchPreferenceCompat mPreference;
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (mPreference != null) updateState(mPreference);
            mHandler.postDelayed(this, 1000);
        }
    };

    public ChargingBypassPreferenceController(Context context, String key) {
        super(context, key);
        HealthInterface health = null;
        try {
            health = HealthInterface.getInstance(context);
        } catch (RuntimeException e) {
            // The service is absent on devices without Lineage health support.
        }
        mHealth = health;
    }

    @Override
    public int getAvailabilityStatus() {
        return mHealth != null && mHealth.isChargingBypassSupported()
                ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @Override
    public void updateState(Preference preference) {
        if (mHealth == null) return;
        SwitchPreferenceCompat toggle = (SwitchPreferenceCompat) preference;
        int state = mHealth.getChargingBypassState();
        toggle.setChecked(state == HealthInterface.BYPASS_ACTIVE);
        // An error still offers an explicit recovery request; never enable from it.
        toggle.setEnabled(state == HealthInterface.BYPASS_OFF
                || state == HealthInterface.BYPASS_ACTIVE || state == HealthInterface.BYPASS_ERROR);
        int summary = switch (state) {
            case HealthInterface.BYPASS_ACTIVE -> R.string.charging_bypass_active;
            case HealthInterface.BYPASS_UNPLUGGED -> R.string.charging_bypass_unplugged;
            case HealthInterface.BYPASS_LOW_BATTERY -> R.string.charging_bypass_low_battery;
            case HealthInterface.BYPASS_SCREEN_OFF -> R.string.charging_bypass_screen_off;
            case HealthInterface.BYPASS_ERROR -> R.string.charging_bypass_error;
            default -> R.string.charging_bypass_off;
        };
        toggle.setSummary(summary);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        boolean enable = (Boolean) value;
        if (mHealth.getChargingBypassState() == HealthInterface.BYPASS_ERROR) enable = false;
        boolean success = mHealth.setChargingBypassEnabled(enable);
        updateState(preference);
        if (!success) {
            Toast.makeText(mContext, R.string.charging_bypass_request_failed, Toast.LENGTH_LONG)
                    .show();
        }
        // updateState owns the checked state, including failed transitions.
        return false;
    }

    @Override
    public void onStart() {
        if (getAvailabilityStatus() == AVAILABLE) mHandler.post(mRefresh);
    }

    @Override
    public void onStop() {
        mHandler.removeCallbacks(mRefresh);
    }
}
