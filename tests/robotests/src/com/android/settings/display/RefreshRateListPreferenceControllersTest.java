/*
 * Copyright (C) 2026 The LineageOS Project
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

import static com.android.settings.core.BasePreferenceController.AVAILABLE;
import static com.android.settings.core.BasePreferenceController.UNSUPPORTED_ON_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.provider.Settings;
import android.testing.TestableContext;
import android.view.Display;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.settings.R;
import com.android.settings.testutils.shadow.SettingsShadowResources;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises the real list controllers, Settings provider, widgets and observer lifecycle. */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "en-rUS", shadows = {SettingsShadowResources.class})
public class RefreshRateListPreferenceControllersTest {

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private Context mControllerContext;
    private DisplayManager mDisplayManager;
    private MinRefreshRatePreferenceController mMinController;
    private PeakRefreshRateListPreferenceController mPeakController;
    private ListPreference mMinPreference;
    private ListPreference mPeakPreference;

    @Before
    public void setUp() {
        SettingsShadowResources.overrideResource(R.bool.config_show_min_refresh_rate_switch, true);
        SettingsShadowResources.overrideResource(R.bool.config_show_peak_refresh_rate_switch, true);
        SettingsShadowResources.overrideResource(
                com.android.internal.R.integer.config_defaultPeakRefreshRate, 144);

        mDisplayManager = mock(DisplayManager.class);
        Display display = mock(Display.class);
        Display.Mode[] modes = new Display.Mode[] {
                new Display.Mode(1, 1216, 2688, 60f),
                new Display.Mode(2, 1216, 2688, 90f),
                new Display.Mode(3, 1216, 2688, 120f),
                new Display.Mode(4, 1216, 2688, 144f),
        };
        when(display.getMode()).thenReturn(modes[0]);
        when(display.getSupportedModes()).thenAnswer(invocation -> modes.clone());
        when(mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(display);
        mContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        // SearchIndexablesProvider supplies a non-visual Context. It must not need getDisplay().
        mControllerContext = spy(mContext);
        doThrow(new UnsupportedOperationException("Non-visual Context"))
                .when(mControllerContext).getDisplay();

        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.MIN_REFRESH_RATE,
                60f);
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.PEAK_REFRESH_RATE,
                144f);

        mMinController = new MinRefreshRatePreferenceController(mControllerContext);
        mPeakController = new PeakRefreshRateListPreferenceController(mControllerContext,
                "max_refresh_rate");
        PreferenceManager manager = new PreferenceManager(mContext);
        PreferenceScreen screen = manager.createPreferenceScreen(mContext);
        mMinPreference = new ListPreference(mContext);
        mMinPreference.setKey("min_refresh_rate");
        screen.addPreference(mMinPreference);
        mPeakPreference = new ListPreference(mContext);
        mPeakPreference.setKey("max_refresh_rate");
        screen.addPreference(mPeakPreference);
        mMinController.displayPreference(screen);
        mPeakController.displayPreference(screen);
        mMinController.onStart();
        mPeakController.onStart();
    }

    @After
    public void tearDown() {
        if (mMinController != null) {
            mMinController.onStop();
        }
        if (mPeakController != null) {
            mPeakController.onStop();
        }
    }

    @Test
    public void lowerPeakBelowMinimum_updatesBothSettingsAndSummaries() {
        mMinController.onPreferenceChange(mMinPreference, "144.00");

        mPeakController.onPreferenceChange(mPeakPreference, "60.00");
        dispatchSettingsNotifications();

        assertThat(readSetting(Settings.System.MIN_REFRESH_RATE)).isEqualTo(60f);
        assertThat(readSetting(Settings.System.PEAK_REFRESH_RATE)).isEqualTo(60f);
        assertThat(mMinPreference.getSummary().toString()).isEqualTo("60Hz");
        assertThat(mPeakPreference.getSummary().toString()).isEqualTo("60Hz");
    }

    @Test
    public void raiseMinimumAbovePeak_updatesBothSettingsAndSummaries() {
        mPeakController.onPreferenceChange(mPeakPreference, "90.00");

        mMinController.onPreferenceChange(mMinPreference, "120.00");
        dispatchSettingsNotifications();

        assertThat(readSetting(Settings.System.MIN_REFRESH_RATE)).isEqualTo(120f);
        assertThat(readSetting(Settings.System.PEAK_REFRESH_RATE)).isEqualTo(120f);
        assertThat(mMinPreference.getSummary().toString()).isEqualTo("120Hz");
        assertThat(mPeakPreference.getSummary().toString()).isEqualTo("120Hz");
    }

    @Test
    public void highestPeak_preservesInfinityWhenMinimumChanges() {
        mPeakController.onPreferenceChange(mPeakPreference, "Infinity");

        mMinController.onPreferenceChange(mMinPreference, "120.00");
        dispatchSettingsNotifications();

        assertThat(readSetting(Settings.System.PEAK_REFRESH_RATE)).isPositiveInfinity();
        assertThat(mPeakPreference.getValue()).isEqualTo("Infinity");
        assertThat(mPeakPreference.getSummary().toString()).isEqualTo("144Hz");
        assertThat(mMinPreference.getSummary().toString()).isEqualTo("120Hz");
    }

    @Test
    public void unboundedPeak_preservesZeroWhenMinimumChanges() {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.PEAK_REFRESH_RATE,
                0f);

        mMinController.onPreferenceChange(mMinPreference, "60.00");
        dispatchSettingsNotifications();

        assertThat(readSetting(Settings.System.MIN_REFRESH_RATE)).isEqualTo(60f);
        assertThat(readSetting(Settings.System.PEAK_REFRESH_RATE)).isEqualTo(0f);
        assertThat(mMinPreference.getSummary().toString()).isEqualTo("60Hz");
        assertThat(mPeakPreference.getValue()).isEqualTo("Infinity");
        assertThat(mPeakPreference.getSummary().toString()).isEqualTo("144Hz");
    }

    @Test
    public void minimumUnset_summaryShowsLowestSupportedRate() {
        Settings.System.putString(mContext.getContentResolver(), Settings.System.MIN_REFRESH_RATE,
                null);

        dispatchSettingsNotifications();

        assertThat(mMinPreference.getSummary().toString()).isEqualTo("60Hz");
    }

    @Test
    public void minimumZero_summaryShowsLowestSupportedRate() {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.MIN_REFRESH_RATE,
                0f);

        dispatchSettingsNotifications();

        assertThat(mMinPreference.getSummary().toString()).isEqualTo("60Hz");
    }

    @Test
    public void nonVisualContext_controllerIsAvailableWithoutCallingGetDisplay() {
        assertThat(mMinController.getAvailabilityStatus()).isEqualTo(AVAILABLE);
        verify(mControllerContext, never()).getDisplay();
    }

    @Test
    public void missingDefaultDisplay_minimumControllerIsUnavailable() {
        when(mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(null);

        MinRefreshRatePreferenceController controller =
                new MinRefreshRatePreferenceController(mControllerContext);

        assertThat(controller.getAvailabilityStatus()).isEqualTo(UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void stop_unregistersSettingsObservers() {
        mMinController.onStop();
        mPeakController.onStop();

        assertThat(shadowOf(mContext.getContentResolver()).getContentObservers(
                Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE))).isEmpty();
        assertThat(shadowOf(mContext.getContentResolver()).getContentObservers(
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE))).isEmpty();
    }

    private float readSetting(String key) {
        return Settings.System.getFloat(mContext.getContentResolver(), key, -1f);
    }

    private void dispatchSettingsNotifications() {
        // Model SettingsProvider notifying observers after a successful setting write.
        ContentResolver resolver = mContext.getContentResolver();
        resolver.notifyChange(Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE), null, 0);
        resolver.notifyChange(Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE), null, 0);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
