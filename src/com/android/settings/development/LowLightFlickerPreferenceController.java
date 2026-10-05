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

package com.android.settings.development;

import android.content.Context;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.internal.display.DcDimmingSettings;
import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.development.DeveloperOptionsPreferenceController;

/** Stores the requested switch; the display service applies it at the next screen-off. */
public class LowLightFlickerPreferenceController extends DeveloperOptionsPreferenceController
        implements Preference.OnPreferenceChangeListener, PreferenceControllerMixin {
    private static final String PREFERENCE_KEY = "low_light_flicker_optimization";

    public LowLightFlickerPreferenceController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return PREFERENCE_KEY;
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportSoftwareDcDimming);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return Settings.System.putInt(mContext.getContentResolver(),
                DcDimmingSettings.ENABLED, (Boolean) newValue ? 1 : 0);
    }

    @Override
    public void updateState(Preference preference) {
        ((TwoStatePreference) preference).setChecked(Settings.System.getInt(
                mContext.getContentResolver(), DcDimmingSettings.ENABLED, 0) != 0);
    }

    // Disabling the developer options master switch only disables this preference's UI.
    // The inherited implementation preserves the user's DC setting, as stock does.
}
