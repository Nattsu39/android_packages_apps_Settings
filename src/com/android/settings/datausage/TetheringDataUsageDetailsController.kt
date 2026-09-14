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

package com.android.settings.datausage

import android.annotation.SuppressLint
import android.app.usage.NetworkStats
import android.content.Context
import android.net.NetworkTemplate
import android.net.TetheredClient
import android.net.TetheringInterface
import android.net.TetheringManager
import android.util.Log
import androidx.annotation.OpenForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import com.android.settings.datausage.lib.DataUsageFormatter
import com.android.settings.datausage.lib.NetworkStatsRepository
import com.android.settings.datausage.lib.NetworkUsageDetailsData
import com.android.settings.wifi.tether.WifiTetherClientRepository
import com.android.settings.wifi.tether.WifiTetherClientRepository.ClientRecord
import com.android.settings.wifi.tether.WifiTetherUsageDetailsSettings
import com.android.settings.wifi.tether.TetheringUncategorizedReason
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.EnumMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows per-endpoint tethering usage recorded under UID_TETHERING tagged stats.
 */
@OpenForTesting
open class TetheringDataUsageDetailsController(context: Context, preferenceKey: String) :
    BasePreferenceController(context, preferenceKey) {

    private val dataUsageFormatter = DataUsageFormatter(context)
    private val tetheringManager = context.getSystemService(TetheringManager::class.java)
    private val clientRepository = WifiTetherClientRepository.getInstance(context)

    private lateinit var preference: PreferenceCategory
    private var networkStatsRepository: NetworkStatsRepository? = null
    private var viewLifecycleOwner: LifecycleOwner? = null
    private var isEnabledForTethering = false
    private var isCallbackRegistered = false
    private var usageData = NetworkUsageDetailsData.AllZero
    private var tetheredClients: List<TetheredClient> = emptyList()
    private var tetheredInterfaces: Set<TetheringInterface> = emptySet()
    private var endpointUsageByTag: Map<Int, EndpointUsage>? = null
    private var unclassifiedUsageBytes: Long? = null
    private var reportedTotalUsageBytes: Long? = null
    private var persistedClients: List<ClientRecord> = emptyList()
    private var usageQueryGeneration = 0

    private val tetheringCallback =
        object : TetheringManager.TetheringEventCallback {
            override fun onClientsChanged(clients: Collection<TetheredClient>) {
                tetheredClients = clients.toList()
                refreshEndpointUsages()
            }

            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                tetheredInterfaces = interfaces.toSet()
                refreshPreference()
            }
        }

    override fun getAvailabilityStatus() = AVAILABLE

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)!!
        refreshPreference()
    }

    fun initForTethering(template: NetworkTemplate) {
        networkStatsRepository = NetworkStatsRepository(mContext, template)
        isEnabledForTethering = true
        refreshEndpointUsages()
    }

    fun update(data: NetworkUsageDetailsData) {
        usageData = data
        refreshEndpointUsages()
    }

    override fun onViewCreated(viewLifecycleOwner: LifecycleOwner) {
        this.viewLifecycleOwner = viewLifecycleOwner
        viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    registerCallback()
                    refreshEndpointUsages()
                }

                override fun onStop(owner: LifecycleOwner) {
                    unregisterCallback()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerCallback() {
        if (!isEnabledForTethering || isCallbackRegistered || tetheringManager == null) {
            return
        }
        try {
            tetheringManager.registerTetheringEventCallback(
                mContext.mainExecutor,
                tetheringCallback,
            )
            isCallbackRegistered = true
        } catch (e: RuntimeException) {
            isCallbackRegistered = false
        }
    }

    private fun unregisterCallback() {
        if (!isCallbackRegistered || tetheringManager == null) {
            return
        }
        try {
            tetheringManager.unregisterTetheringEventCallback(tetheringCallback)
        } catch (e: RuntimeException) {
            // The callback may already be gone if tethering service restarted.
        }
        isCallbackRegistered = false
    }

    private fun refreshPreference() {
        if (!::preference.isInitialized) {
            return
        }
        preference.isVisible = isEnabledForTethering
        if (!isEnabledForTethering) {
            return
        }

        preference.removeAll()
        addUsageRow(
            key = KEY_SHARED_TOTAL,
            title = mContext.getString(R.string.data_usage_tethering_shared_total),
            usage = reportedTotalUsageBytes ?: usageData.totalUsage,
            selectable = true,
            onClick = { WifiTetherUsageDetailsSettings.launch(
                mContext,
                getMetricsCategory(),
                null,
            ) },
        )

        val activeTypes = getActiveTetheringTypes()
        for (client in persistedClients) {
            addEndpointRow(
                key = getClientPreferenceKey(client),
                title = getPersistedClientLabel(client),
                usage = endpointUsageByTag?.get(client.statsTag) ?: endpointZeroIfLoaded(),
                client = client,
            )
        }

        unclassifiedUsageBytes?.takeIf { it > 0L }?.let {
            addUsageRow(
                key = KEY_UNCLASSIFIED,
                title = mContext.getString(R.string.wifi_tether_usage_details_uncategorized),
                usage = it,
            )
        }

        addActiveTypePlaceholder(TetheringManager.TETHERING_WIFI, KEY_WIFI_HOTSPOT,
            R.string.wifi_hotspot_checkbox_text, activeTypes)
        addActiveTypePlaceholder(TetheringManager.TETHERING_USB, KEY_USB_TETHERING,
            R.string.tether_settings_title_usb, activeTypes)
        addActiveTypePlaceholder(TetheringManager.TETHERING_BLUETOOTH, KEY_BLUETOOTH_TETHERING,
            R.string.tether_settings_title_bluetooth, activeTypes)

        if (preference.preferenceCount == 1 && usageData.totalUsage <= 0L) {
            addMessageRow(R.string.data_usage_tethering_no_active_clients)
        }
    }

    private fun addActiveTypePlaceholder(
        type: Int,
        key: String,
        titleResId: Int,
        activeTypes: Set<Int>,
    ) {
        val hasPersistedClient = persistedClients.any {
            WifiTetherClientRepository.normalizeTetheringType(it.tetheringType) == type
        }
        if (activeTypes.contains(type) && !hasPersistedClient) {
            addEndpointRow(
                key = key,
                title = mContext.getString(titleResId),
                usage = null,
                client = null,
            )
        }
    }

    private fun refreshEndpointUsages() {
        usageQueryGeneration++
        val generation = usageQueryGeneration
        val repository = networkStatsRepository
        val owner = viewLifecycleOwner
        endpointUsageByTag = null
        unclassifiedUsageBytes = null
        reportedTotalUsageBytes = null
        refreshPreference()

        val data = usageData
        if (!isEnabledForTethering || repository == null || owner == null ||
            data.range.lower >= data.range.upper
        ) {
            return
        }

        val activeClients = tetheredClients.toList()
        val activeInterfaces = tetheredInterfaces.toSet()
        owner.lifecycleScope.launch {
            val range = data.range
            val result = withContext(Dispatchers.Default) {
                clientRepository.recordClients(activeClients)
                clientRepository.refreshUsage()
                val clients = clientRepository.clients
                val usageByTag = repository.queryTaggedBuckets(range.lower, range.upper)
                    .asSequence()
                    .filter {
                        it.uid == NetworkStats.Bucket.UID_TETHERING &&
                            it.tag != NetworkStats.Bucket.TAG_NONE
                    }
                    .groupingBy { it.tag }
                    .fold(EndpointUsage()) { usage, bucket ->
                        usage + EndpointUsage(bucket.rxBytes, bucket.txBytes)
                    }
                val taggedUsage = usageByTag.values.sumOf { it.totalBytes }
                val attributedUsage = clients.sumOf {
                    usageByTag[it.statsTag]?.totalBytes ?: 0L
                }
                val totalUsage = maxOf(data.totalUsage, taggedUsage)
                val untaggedUsage = (totalUsage - taggedUsage).coerceAtLeast(0L)
                val unclassifiedUsage = (totalUsage - attributedUsage).coerceAtLeast(0L)
                QueryResult(
                    clients = clients,
                    usageByTag = usageByTag,
                    totalUsage = totalUsage,
                    unclassifiedUsage = unclassifiedUsage,
                    unclassifiedReasons = buildUnclassifiedReasonUsage(
                        activeClients = activeClients,
                        activeInterfaces = activeInterfaces,
                        clients = clients,
                        usageByTag = usageByTag,
                        untaggedUsage = untaggedUsage,
                        totalUsage = totalUsage,
                        attributedUsage = attributedUsage,
                    ),
                )
            }
            if (usageQueryGeneration == generation) {
                persistedClients = result.clients
                endpointUsageByTag = result.usageByTag
                reportedTotalUsageBytes = result.totalUsage
                unclassifiedUsageBytes = result.unclassifiedUsage
                logUnclassifiedUsage(result)
                refreshPreference()
            }
        }
    }

    private fun buildUnclassifiedReasonUsage(
        activeClients: List<TetheredClient>,
        activeInterfaces: Set<TetheringInterface>,
        clients: List<ClientRecord>,
        usageByTag: Map<Int, EndpointUsage>,
        untaggedUsage: Long,
        totalUsage: Long,
        attributedUsage: Long,
    ): Map<TetheringUncategorizedReason, EndpointUsage> {
        val reasons = EnumMap<TetheringUncategorizedReason, EndpointUsage>(
            TetheringUncategorizedReason::class.java,
        )
        val clientTags = clients.mapTo(HashSet()) { it.statsTag }
        usageByTag
            .filterKeys { tag -> tag != NetworkStats.Bucket.TAG_NONE && !clientTags.contains(tag) }
            .values
            .forEach { reasons.add(TetheringUncategorizedReason.UNMATCHED_CLIENT, it) }

        if (untaggedUsage > 0L) {
            reasons.add(
                inferUntaggedReason(activeClients, activeInterfaces),
                EndpointUsage.fromTotal(untaggedUsage),
            )
        }

        val reasonUsage = reasons.values.sumOf { it.totalBytes }
        val counterGap = (totalUsage - attributedUsage - reasonUsage).coerceAtLeast(0L)
        if (counterGap > 0L) {
            reasons.add(
                TetheringUncategorizedReason.COUNTER_GAP,
                EndpointUsage.fromTotal(counterGap),
            )
        }
        return reasons
    }

    private fun inferUntaggedReason(
        activeClients: List<TetheredClient>,
        activeInterfaces: Set<TetheringInterface>,
    ): TetheringUncategorizedReason {
        val activeTypes = (activeClients.map { it.tetheringType } + activeInterfaces.map { it.type })
            .map(::normalizeTetheringType)
            .toSet()
        if (activeTypes.any { it !in KNOWN_TETHERING_TYPES }) {
            return TetheringUncategorizedReason.UNKNOWN_IFACE
        }
        if (activeClients.any { it.addresses.isEmpty() }) {
            return TetheringUncategorizedReason.NO_IP
        }
        if (activeClients.any { it.hasOnlyIpv6Addresses() }) {
            return TetheringUncategorizedReason.IPV6_UNMATCHED
        }
        return TetheringUncategorizedReason.NON_CLIENT_TETHER
    }

    private fun TetheredClient.hasOnlyIpv6Addresses(): Boolean {
        val addresses = addresses
        return addresses.isNotEmpty() &&
            addresses.none { it.address.address is Inet4Address } &&
            addresses.any { it.address.address is Inet6Address }
    }

    private fun logUnclassifiedUsage(result: QueryResult) {
        if (result.unclassifiedUsage <= 0L || result.unclassifiedReasons.isEmpty()) {
            return
        }
        val details = result.unclassifiedReasons.entries.joinToString {
            "${it.key}=${it.value.totalBytes}"
        }
        Log.d(
            TAG,
            "Uncategorized tethering usage total=${result.unclassifiedUsage}, " +
                "reportedTotal=${result.totalUsage}, reasons={$details}",
        )
    }

    private fun EnumMap<TetheringUncategorizedReason, EndpointUsage>.add(
        reason: TetheringUncategorizedReason,
        usage: EndpointUsage,
    ) {
        this[reason] = (this[reason] ?: EndpointUsage()) + usage
    }

    private fun addEndpointRow(
        key: String,
        title: String,
        usage: EndpointUsage?,
        client: ClientRecord?,
    ) {
        preference.addPreference(
            Preference(preference.context).apply {
                this.key = key
                this.title = title
                summary =
                    usage?.let(::formatEndpointUsage)
                        ?: mContext.getString(R.string.data_usage_tethering_usage_loading)
                isSelectable = client != null
                if (client != null) {
                    setOnPreferenceClickListener {
                        WifiTetherUsageDetailsSettings.launch(
                            mContext,
                            getMetricsCategory(),
                            client,
                        )
                        true
                    }
                }
            }
        )
    }

    private fun endpointZeroIfLoaded(): EndpointUsage? =
        if (endpointUsageByTag == null) null else EndpointUsage()

    private fun normalizeTetheringType(type: Int) =
        if (type == TetheringManager.TETHERING_WIFI_P2P) TetheringManager.TETHERING_WIFI else type

    private fun getActiveTetheringTypes(): Set<Int> =
        (tetheredClients.map { it.tetheringType } + tetheredInterfaces.map { it.type })
            .map(::normalizeTetheringType)
            .toSet()

    private fun addUsageRow(
        key: String,
        title: String,
        usage: Long,
        selectable: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        preference.addPreference(
            Preference(preference.context).apply {
                this.key = key
                this.title = title
                summary = dataUsageFormatter.formatDataUsage(usage)
                isSelectable = selectable
                onClick?.let { click ->
                    setOnPreferenceClickListener {
                        click()
                        true
                    }
                }
            }
        )
    }

    private fun formatEndpointUsage(usage: EndpointUsage): String =
        mContext.getString(
            R.string.wifi_tether_usage_details_usage_split,
            dataUsageFormatter.formatDataUsage(usage.totalBytes),
            dataUsageFormatter.formatDataUsage(usage.txBytes),
            dataUsageFormatter.formatDataUsage(usage.rxBytes),
        )

    private fun addMessageRow(titleResId: Int) {
        preference.addPreference(
            Preference(preference.context).apply {
                key = KEY_EMPTY
                setTitle(titleResId)
                isSelectable = false
            }
        )
    }

    private fun getPersistedClientLabel(client: ClientRecord): String {
        val clientLabel = if (client.hostname.isNullOrBlank()) {
            client.macAddress
        } else {
            mContext.getString(
                R.string.data_usage_tethering_client_label,
                client.hostname,
                client.macAddress,
            )
        }
        val type = WifiTetherClientRepository.normalizeTetheringType(client.tetheringType)
        if (type == TetheringManager.TETHERING_WIFI) {
            return clientLabel
        }
        val typeTitle = when (type) {
            TetheringManager.TETHERING_USB -> R.string.tether_settings_title_usb
            TetheringManager.TETHERING_BLUETOOTH -> R.string.tether_settings_title_bluetooth
            else -> R.string.ethernet_tether_checkbox_text
        }
        return mContext.getString(
            R.string.data_usage_tethering_typed_client_label,
            mContext.getString(typeTitle),
            clientLabel,
        )
    }

    private fun getClientPreferenceKey(client: ClientRecord) =
        KEY_CLIENT_PREFIX + client.tetheringType + "_" + client.macAddress

    private data class EndpointUsage(
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
    ) {
        val totalBytes: Long
            get() = rxBytes + txBytes

        operator fun plus(other: EndpointUsage) =
            EndpointUsage(rxBytes + other.rxBytes, txBytes + other.txBytes)

        companion object {
            fun fromTotal(bytes: Long) = EndpointUsage(rxBytes = bytes.coerceAtLeast(0L))
        }
    }

    private companion object {
        const val TAG = "TetheringUsageDetails"
        const val KEY_SHARED_TOTAL = "tethering_usage_shared_total"
        const val KEY_WIFI_HOTSPOT = "tethering_usage_wifi_hotspot"
        const val KEY_CLIENT_PREFIX = "tethering_usage_client_"
        const val KEY_UNCLASSIFIED = "tethering_usage_unclassified"
        const val KEY_USB_TETHERING = "tethering_usage_usb"
        const val KEY_BLUETOOTH_TETHERING = "tethering_usage_bluetooth"
        const val KEY_EMPTY = "tethering_usage_empty"
        val KNOWN_TETHERING_TYPES = setOf(
            TetheringManager.TETHERING_WIFI,
            TetheringManager.TETHERING_USB,
            TetheringManager.TETHERING_BLUETOOTH,
            TetheringManager.TETHERING_WIFI_P2P,
            TetheringManager.TETHERING_NCM,
            TetheringManager.TETHERING_ETHERNET,
            TetheringManager.TETHERING_VIRTUAL,
        )
    }

    private data class QueryResult(
        val clients: List<ClientRecord>,
        val usageByTag: Map<Int, EndpointUsage>,
        val totalUsage: Long,
        val unclassifiedUsage: Long,
        val unclassifiedReasons: Map<TetheringUncategorizedReason, EndpointUsage>,
    )
}
