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

/** Internal buckets explaining why tethering traffic could not be attributed to a client row. */
public enum TetheringUncategorizedReason {
    /** Traffic has a per-client stats tag, but Settings does not know the matching client yet. */
    UNMATCHED_CLIENT,
    /** The active tethered client list has clients without a lease/address to match against. */
    NO_IP,
    /** The active tethering type/interface is not one of the Settings-supported client types. */
    UNKNOWN_IFACE,
    /** Active clients only expose IPv6 leases while the counters cannot be matched to a client. */
    IPV6_UNMATCHED,
    /** Untagged UID_TETHERING traffic that does not describe a user-visible downstream client. */
    NON_CLIENT_TETHER,
    /** Positive remainder between total counters and per-client/per-reason accounting. */
    COUNTER_GAP,
}
