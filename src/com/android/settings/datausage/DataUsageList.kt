/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.settings.SettingsEnums
import android.content.Context
import android.content.Intent
import android.net.NetworkPolicy
import android.net.NetworkTemplate
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.util.EventLog
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.annotation.OpenForTesting
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.viewModels
import com.android.settings.R
import com.android.settings.Utils
import com.android.settings.dashboard.DashboardFragment
import com.android.settings.datausage.lib.BillingCycleRepository
import com.android.settings.datausage.lib.NetworkUsageData
import com.android.settings.network.telephony.SubscriptionRepository
import com.android.settingslib.spa.framework.util.collectLatestWithLifecycle
import com.android.settingslib.spaprivileged.framework.common.userManager
import com.android.settingslib.widget.LayoutPreference
import kotlin.jvm.optionals.getOrNull

// LINT.IfChange
/**
 * Panel showing data usage history across various networks, including options to inspect based on
 * usage cycle and control through [NetworkPolicy].
 */
@OpenForTesting
open class DataUsageList : DashboardFragment() {
    @VisibleForTesting
    var template: NetworkTemplate? = null
        private set

    @VisibleForTesting
    var subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        private set

    private lateinit var billingCycleRepository: BillingCycleRepository

    private var usageAmount: LayoutPreference? = null
    private var dataUsageListAppsController: DataUsageListAppsController? = null
    private var dataUsageListHeaderController: DataUsageListHeaderController? = null

    private val viewModel: DataUsageListViewModel by viewModels()

    override fun getMetricsCategory() = SettingsEnums.DATA_USAGE_LIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billingCycleRepository = BillingCycleRepository(requireContext())
        if (requireContext().userManager.isGuestUser) {
            Log.e(TAG, "This setting isn't available for guest user")
            EventLog.writeEvent(0x534e4554, "262741858", -1 /* UID */, "Guest user")
            finish()
            return
        }
        if (!billingCycleRepository.isBandwidthControlEnabled()) {
            Log.w(TAG, "No bandwidth control; leaving")
            finish()
            return
        }
        usageAmount = findPreference(KEY_USAGE_AMOUNT)
        processArgument()
        val template = template
        if (template == null) {
            Log.e(TAG, "No template; leaving")
            finish()
            return
        }
        dataUsageListAppsController =
            use(DataUsageListAppsController::class.java).apply { init(template) }

        updateSelectedUsageAmount(NetworkUsageData.AllZero)
        updateWarning()
    }

    private fun updateWarning() {
        val template = template ?: return
        val preference = findPreference<LayoutPreference>(KEY_WARNING) ?: return
        val textView = preference.findViewById<TextView>(R.id.text) ?: return
        val context = requireContext()
        val warningText = when {
            template.matchRule != NetworkTemplate.MATCH_WIFI ->
                context.getString(R.string.operator_warning)
            Utils.isMobileDataCapable(context) ->
                context.getString(R.string.non_carrier_data_usage_warning)
            else -> null
        }
        preference.isVisible = warningText != null
        if (warningText != null) {
            textView.text = warningText
        }
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        val template = template ?: return
        viewModel.templateFlow.value = template
        dataUsageListHeaderController =
            DataUsageListHeaderController(
                setPinnedHeaderView(R.layout.apps_filter_spinner),
                template,
                metricsCategory,
                viewLifecycleOwner,
                viewModel.cyclesFlow,
                ::updateSelectedCycle,
            )
        if (template.matchRule == NetworkTemplate.MATCH_MOBILE &&
            SubscriptionManager.isValidSubscriptionId(subId)
        ) {
            billingCycleRepository
                .isModifiableFlow(subId)
                .collectLatestWithLifecycle(viewLifecycleOwner, action = ::updatePolicy)
        } else {
            updatePolicy(false)
        }
        viewModel.cyclesFlow.collectLatestWithLifecycle(viewLifecycleOwner) { cycles ->
            dataUsageListAppsController?.updateCycles(cycles)
        }
        viewModel.selectedUsageDataFlow.collectLatestWithLifecycle(viewLifecycleOwner) { usageData ->
            updateSelectedUsageData(usageData)
        }
        finishIfSubscriptionDisabled()
    }

    private fun finishIfSubscriptionDisabled() {
        if (SubscriptionManager.isUsableSubscriptionId(subId)) {
            SubscriptionRepository(requireContext())
                .isSubscriptionEnabledFlow(subId)
                .collectLatestWithLifecycle(viewLifecycleOwner) { isSubscriptionEnabled ->
                    if (!isSubscriptionEnabled) finish()
                }
        }
    }

    override fun getPreferenceScreenResId() = R.xml.data_usage_list

    override fun getLogTag() = TAG

    override fun getPreferenceScreenBindingKey(context: Context): String {
        return DataUsageListScreen.KEY
    }

    private fun processArgument() {
        arguments?.let {
            subId = it.getInt(EXTRA_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            template = it.getParcelable(EXTRA_NETWORK_TEMPLATE, NetworkTemplate::class.java)
        }
        if (template == null && subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId =
                intent.getIntExtra(
                    Settings.EXTRA_SUB_ID,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                )
            val localIntent: Intent = intent.clone() as Intent
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId =
                    getPreferenceScreenBindingArgs(requireContext())?.getInt(Settings.EXTRA_SUB_ID)
                        ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                Log.d(
                    TAG,
                    "processArgument: get subId from ScreenBindingArgs and reset subId into intent.",
                )
                // Add this extra into the intent because it is required in the
                // DataUsageUtils.getMobileNetworkTemplateFromSubId.
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    localIntent.putExtra(Settings.EXTRA_SUB_ID, subId)
                }
            }
            template =
                intent.getParcelableExtra(
                    Settings.EXTRA_NETWORK_TEMPLATE,
                    NetworkTemplate::class.java,
                )
                    ?: DataUsageUtils.getMobileNetworkTemplateFromSubId(context, localIntent)
                        .getOrNull()
        }
    }

    /** Update the billing-cycle configuration button to reflect [NetworkPolicy]. */
    private fun updatePolicy(isModifiable: Boolean) {
        dataUsageListHeaderController?.setConfigButtonVisible(isModifiable)
    }

    /** Updates the chart and detail data when initial loaded or selected cycle changed. */
    private fun updateSelectedCycle(usageData: NetworkUsageData) {
        Log.d(TAG, "showing cycle $usageData")

        viewModel.selectedCycleFlow.value = usageData
    }

    /** Updates usage summary and applications data usage after selected range is aggregated. */
    private fun updateSelectedUsageData(usageData: NetworkUsageData) {
        updateSelectedUsageAmount(usageData)
        updateApps(usageData)
    }

    private fun updateSelectedUsageAmount(usageData: NetworkUsageData) {
        usageAmount
            ?.findViewById<TextView>(R.id.data_usage_amount)
            ?.text = usageData.getDataUsedString(requireContext())
    }

    /** Updates applications data usage. */
    private fun updateApps(usageData: NetworkUsageData) {
        dataUsageListAppsController?.update(
            subId = subId,
            startTime = usageData.startTime,
            endTime = usageData.endTime,
            totalUsage = usageData.usage,
        )
    }

    companion object {
        const val EXTRA_SUB_ID = "sub_id"
        const val EXTRA_NETWORK_TEMPLATE = "network_template"

        private const val TAG = "DataUsageList"
        private const val KEY_USAGE_AMOUNT = "usage_amount"

        @VisibleForTesting const val KEY_WARNING = "warning"
    }
}
// LINT.ThenChange(DataUsageListScreen.kt)
