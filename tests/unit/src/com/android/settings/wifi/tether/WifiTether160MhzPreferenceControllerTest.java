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

import static com.google.common.truth.Truth.assertThat;

import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;

import org.junit.Test;

public class WifiTether160MhzPreferenceControllerTest {

    @Test
    public void is160MhzChannelWidth_80Plus80IsNotContinuous160() {
        assertThat(WifiTether160MhzPreferenceController.is160MhzChannelWidth(
                ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ)).isFalse();
    }

    @Test
    public void is160MhzChannelWidth_160IsSupported() {
        assertThat(WifiTether160MhzPreferenceController.is160MhzChannelWidth(
                ScanResult.CHANNEL_WIDTH_160MHZ)).isTrue();
    }

    @Test
    public void is5g160MhzEnabled_readsConfiguredMaximumBandwidth() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_160MHZ)
                .build();

        assertThat(WifiTether160MhzPreferenceController.is5g160MhzEnabled(config)).isTrue();
    }

    @Test
    public void is5g160MhzEnabled_autoBandwidthIsDisabled() {
        SoftApConfiguration config = new SoftApConfiguration.Builder()
                .setMaxChannelBandwidth(SoftApInfo.CHANNEL_WIDTH_AUTO)
                .build();

        assertThat(WifiTether160MhzPreferenceController.is5g160MhzEnabled(config)).isFalse();
    }
}
