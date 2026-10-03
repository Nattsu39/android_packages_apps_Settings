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

package com.android.settings.testutils.shadow;

import static android.os.Build.VERSION_CODES.R;

import android.content.res.ApkAssets;
import android.content.res.AssetManager;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowArscApkAssets9;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Maps the ROM's Lineage resource path to the real APK supplied by the test module. */
@Implements(value = ApkAssets.class, minSdk = R, isInAndroidSdk = false)
public class ShadowLineageApkAssets extends ShadowArscApkAssets9 {

    private static final String LINEAGE_APK_PATH =
            ReflectionHelpers.getStaticField(AssetManager.class, "LINEAGE_APK_PATH");
    private static final String RESOURCE_APK = "org.lineageos.platform-res.apk";

    @Implementation(minSdk = R)
    protected static ApkAssets loadFromPath(String path, int flags) throws IOException {
        if (LINEAGE_APK_PATH.equals(path)) {
            path = getLineageResourcePath().toString();
        }
        return ShadowArscApkAssets9.loadFromPath(path, flags);
    }

    private static synchronized Path getLineageResourcePath() throws IOException {
        Path directory = RuntimeEnvironment.getTempDirectory()
                .createIfNotExists("lineage-framework-res");
        Path apk = directory.resolve(RESOURCE_APK);
        if (!Files.exists(apk)) {
            try (InputStream input = ShadowLineageApkAssets.class
                    .getResourceAsStream("/" + RESOURCE_APK)) {
                if (input == null) {
                    throw new IOException("Missing test resource: " + RESOURCE_APK);
                }
                Files.copy(input, apk);
            }
        }
        return apk;
    }
}
