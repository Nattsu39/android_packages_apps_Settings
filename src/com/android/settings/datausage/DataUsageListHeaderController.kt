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

import android.app.DatePickerDialog
import android.net.NetworkTemplate
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.AdapterView
import android.widget.Spinner
import androidx.annotation.OpenForTesting
import androidx.lifecycle.LifecycleOwner
import com.android.settings.R
import com.android.settings.core.SubSettingLauncher
import com.android.settings.datausage.CycleAdapter.SpinnerInterface
import com.android.settings.datausage.lib.NetworkUsageData
import com.android.settingslib.spa.framework.util.collectLatestWithLifecycle
import java.util.Calendar
import kotlinx.coroutines.flow.Flow

@OpenForTesting
open class DataUsageListHeaderController(
    header: View,
    template: NetworkTemplate,
    sourceMetricsCategory: Int,
    viewLifecycleOwner: LifecycleOwner,
    cyclesFlow: Flow<List<NetworkUsageData>>,
    private val updateSelectedCycle: (usageData: NetworkUsageData) -> Unit,
) {
    private val context = header.context

    private val configureButton: View = header.requireViewById(R.id.filter_settings)
    private val cycleSpinner: Spinner = header.requireViewById(R.id.filter_spinner)

    private val cycleListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (suppressSelectionChange) return
            setSelectedCycle(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            // ignored
        }
    }

    private val cycleAdapter = CycleAdapter(context, object : SpinnerInterface {
        override fun setAdapter(cycleAdapter: CycleAdapter) {
            cycleSpinner.adapter = cycleAdapter
        }

        override fun getSelectedItem() = cycleSpinner.selectedItem

        override fun setSelection(position: Int) {
            cycleSpinner.setSelection(position)
            if (cycleSpinner.onItemSelectedListener == null) {
                cycleSpinner.onItemSelectedListener = cycleListener
            } else if (!suppressSelectionChange) {
                setSelectedCycle(position)
            }
        }
    })

    private var cycles: List<NetworkUsageData> = emptyList()
    private var cycleUsageData: List<NetworkUsageData?> = emptyList()
    private var customDateItem: CycleAdapter.CycleItem? = null
    private var customRangeItem: CycleAdapter.CycleItem? = null
    private var lastSelectedUsageItem: CycleAdapter.CycleItem? = null
    private var suppressSelectionChange = false

    init {
        configureButton.setOnClickListener {
            val args = Bundle().apply {
                putParcelable(DataUsageList.EXTRA_NETWORK_TEMPLATE, template)
            }
            SubSettingLauncher(context).apply {
                setDestination(BillingCycleSettings::class.java.name)
                setTitleRes(R.string.billing_cycle)
                setSourceMetricsCategory(sourceMetricsCategory)
                setArguments(args)
            }.launch()
        }
        cycleSpinner.visibility = View.GONE
        cycleSpinner.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                // Ignore TYPE_VIEW_SELECTED or TalkBack will speak for it at onResume.
                if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) return
                super.sendAccessibilityEvent(host, eventType)
            }
        }

        cyclesFlow.collectLatestWithLifecycle(viewLifecycleOwner) {
            cycles = it
            updateCycleData()
        }
    }

    open fun setConfigButtonVisible(visible: Boolean) {
        configureButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateCycleData(selectedItem: CycleAdapter.CycleItem? = null) {
        val displayItems = buildCycleItems()
        cycleUsageData = displayItems.map { it.usageData }
        if (displayItems.isEmpty()) {
            cycleSpinner.visibility = View.GONE
            return
        }
        suppressSelectionChange = true
        cycleAdapter.updateCycleItems(displayItems.map { it.item })
        val selectedPosition = selectedItem?.let { cycleAdapter.getPosition(it) }
            ?.takeIf { it >= 0 }
            ?: findPreferredCyclePosition()
        cycleSpinner.setSelection(selectedPosition)
        suppressSelectionChange = false
        if (selectedPosition >= 0) {
            setSelectedCycle(selectedPosition)
        }
        cycleSpinner.visibility = View.VISIBLE
    }

    private fun findPreferredCyclePosition(): Int {
        val selectedItem = lastSelectedUsageItem
            ?: cycleSpinner.selectedItem as? CycleAdapter.CycleItem
        return selectedItem?.let { cycleAdapter.findNearestPosition(it) } ?: 0
    }

    private fun setSelectedCycle(position: Int) {
        val cycleItem = cycleAdapter.getItem(position) ?: return
        when (cycleItem.type) {
            CycleAdapter.CycleItem.TYPE_DATE_PICKER -> showDatePicker()
            CycleAdapter.CycleItem.TYPE_RANGE_PICKER -> showRangePicker()
            else -> selectUsageItem(position, cycleItem)
        }
    }

    private fun selectUsageItem(position: Int, cycleItem: CycleAdapter.CycleItem) {
        lastSelectedUsageItem = cycleItem
        updateSelectedCycle(
            cycleUsageData.getOrNull(position)
                ?: NetworkUsageData(cycleItem.start, cycleItem.end, 0L)
        )
    }

    private fun buildCycleItems(): List<CycleDisplayItem> {
        val displayItems = mutableListOf<CycleDisplayItem>()
        cycles.filter { it.usage > 0 }.forEach {
            displayItems += CycleDisplayItem(
                CycleAdapter.CycleItem(context, it.startTime, it.endTime),
                it,
            )
        }
        if (cycles.none { it.usage > 0 }) {
            displayItems += defaultRangeItem()
        }
        customDateItem?.let { displayItems += CycleDisplayItem(it, null) }
        customRangeItem?.let { displayItems += CycleDisplayItem(it, null) }
        displayItems += pickerItem(
            context.getText(R.string.data_usage_select_date),
            CycleAdapter.CycleItem.TYPE_DATE_PICKER,
        )
        displayItems += pickerItem(
            context.getText(R.string.data_usage_select_range),
            CycleAdapter.CycleItem.TYPE_RANGE_PICKER,
        )
        return displayItems
    }

    private fun usageItem(label: CharSequence, startTime: Long, endTime: Long) = CycleDisplayItem(
        CycleAdapter.CycleItem(label, startTime, endTime, CycleAdapter.CycleItem.TYPE_USAGE),
        NetworkUsageData(startTime, endTime, 0L),
    )

    private fun defaultRangeItem(): CycleDisplayItem {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - DEFAULT_RANGE_DAYS * DateUtils.DAY_IN_MILLIS
        return usageItem(context.getText(R.string.data_usage_last_30_days), startTime, endTime)
    }

    private fun pickerItem(label: CharSequence, type: Int) = CycleDisplayItem(
        CycleAdapter.CycleItem(label, 0L, 0L, type),
        null,
    )

    private fun showDatePicker() {
        val initialTime = customDateItem?.start
            ?: lastSelectedUsageItem?.start
            ?: dayStart(System.currentTimeMillis())
        showDatePickerDialog(context.getText(R.string.data_usage_select_date_title), initialTime) {
            startTime ->
            val endTime = addDays(startTime, 1)
            val selectedItem = CycleAdapter.CycleItem(
                context.getString(
                    R.string.data_usage_custom_date_template,
                    formatDate(startTime),
                ),
                startTime,
                endTime,
                CycleAdapter.CycleItem.TYPE_USAGE,
            )
            customDateItem = selectedItem
            updateCycleData(selectedItem)
        }
    }

    private fun showRangePicker() {
        val initialStartTime = customRangeItem?.start
            ?: lastSelectedUsageItem?.start
            ?: dayStart(System.currentTimeMillis())
        showDatePickerDialog(
            context.getText(R.string.data_usage_select_range_start_title),
            initialStartTime,
        ) { startTime ->
            val initialEndTime = customRangeItem?.end?.let { it - 1 }
                ?: lastSelectedUsageItem?.end?.let { it - 1 }
                ?: startTime
            showDatePickerDialog(
                context.getText(R.string.data_usage_select_range_end_title),
                initialEndTime,
            ) { endTime ->
                val lower = minOf(startTime, endTime)
                val upper = addDays(maxOf(startTime, endTime), 1)
                val selectedItem = CycleAdapter.CycleItem(
                    context.getString(
                        R.string.data_usage_custom_range_template,
                        formatDateRange(lower, upper),
                    ),
                    lower,
                    upper,
                    CycleAdapter.CycleItem.TYPE_USAGE,
                )
                customRangeItem = selectedItem
                updateCycleData(selectedItem)
            }
        }
    }

    private fun showDatePickerDialog(
        title: CharSequence,
        initialTime: Long,
        onDateSelected: (Long) -> Unit,
    ) {
        var handled = false
        val calendar = Calendar.getInstance().apply { timeInMillis = initialTime }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                handled = true
                onDateSelected(dayStart(year, month, dayOfMonth))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).apply {
            setTitle(title)
            setOnDismissListener {
                if (!handled) {
                    restoreLastSelectedUsageItem()
                }
            }
            show()
        }
    }

    private fun restoreLastSelectedUsageItem() {
        updateCycleData(lastSelectedUsageItem)
    }

    private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayStart(year: Int, month: Int, dayOfMonth: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, dayOfMonth, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun addDays(time: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = time
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun formatDate(time: Long): String =
        DateUtils.formatDateTime(context, time, DATE_FORMAT)

    private fun formatDateRange(startTime: Long, endTime: Long): String =
        DateUtils.formatDateRange(context, startTime, endTime - 1, DATE_FORMAT)

    private data class CycleDisplayItem(
        val item: CycleAdapter.CycleItem,
        val usageData: NetworkUsageData?,
    )

    private companion object {
        const val DEFAULT_RANGE_DAYS = 30L

        val DATE_FORMAT = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or
            DateUtils.FORMAT_ABBREV_MONTH
    }
}
