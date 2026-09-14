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
import android.content.Context
import android.text.format.DateUtils
import android.util.Range
import android.view.View
import android.widget.AdapterView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceScreen
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import com.android.settings.datausage.lib.IAppDataUsageDetailsRepository
import com.android.settings.datausage.lib.NetworkUsageDetailsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class AppDataUsageCycleController(context: Context, preferenceKey: String) :
    BasePreferenceController(context, preferenceKey) {

    private lateinit var repository: IAppDataUsageDetailsRepository
    private var onUsageDataUpdated: (NetworkUsageDetailsData) -> Unit = {}
    private lateinit var preference: SpinnerPreference
    private var cycleAdapter: CycleAdapter? = null

    private var usageDetailsDataList: List<NetworkUsageDetailsData> = emptyList()
    private var cycleUsageData: List<NetworkUsageDetailsData?> = emptyList()
    private var customDateItem: CycleAdapter.CycleItem? = null
    private var customRangeItem: CycleAdapter.CycleItem? = null
    private var lastSelectedUsageItem: CycleAdapter.CycleItem? = null
    private var viewLifecycleOwner: LifecycleOwner? = null
    private var suppressSelectionChange = false
    private var usageQueryGeneration = 0

    override fun getAvailabilityStatus() = AVAILABLE

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)!!
        if (cycleAdapter == null) {
            cycleAdapter = CycleAdapter(mContext, preference)
        }
    }

    fun init(
        repository: IAppDataUsageDetailsRepository,
        onUsageDataUpdated: (NetworkUsageDetailsData) -> Unit,
    ) {
        this.repository = repository
        this.onUsageDataUpdated = onUsageDataUpdated
    }

    /**
     * Sets the initial cycles.
     *
     * If coming from a page like DataUsageList where already has a selected cycle, display that
     * before loading to reduce flicker.
     */
    fun setInitialCycles(initialCycles: List<Long>, initialSelectedEndTime: Long) {
        if (initialCycles.isNotEmpty()) {
            cycleAdapter?.setInitialCycleList(initialCycles, initialSelectedEndTime)
            preference.setHasCycles(true)
        }
    }

    fun setInitialSelectedRange(startTime: Long, endTime: Long) {
        if (startTime >= endTime) {
            return
        }
        val todayStart = dayStart(System.currentTimeMillis())
        val yesterdayStart = addDays(todayStart, -1)
        val selectedItem = when {
            startTime == todayStart && endTime == addDays(todayStart, 1) ->
                CycleAdapter.CycleItem(
                    mContext.getText(R.string.data_usage_today),
                    startTime,
                    endTime,
                    CycleAdapter.CycleItem.TYPE_USAGE,
                )
            startTime == yesterdayStart && endTime == todayStart ->
                CycleAdapter.CycleItem(
                    mContext.getText(R.string.data_usage_yesterday),
                    startTime,
                    endTime,
                    CycleAdapter.CycleItem.TYPE_USAGE,
                )
            endTime == addDays(startTime, 1) -> {
                CycleAdapter.CycleItem(
                    mContext.getString(
                        R.string.data_usage_custom_date_template,
                        formatDate(startTime),
                    ),
                    startTime,
                    endTime,
                    CycleAdapter.CycleItem.TYPE_USAGE,
                ).also { customDateItem = it }
            }
            else -> {
                CycleAdapter.CycleItem(
                    mContext.getString(
                        R.string.data_usage_custom_range_template,
                        formatDateRange(startTime, endTime),
                    ),
                    startTime,
                    endTime,
                    CycleAdapter.CycleItem.TYPE_USAGE,
                ).also { customRangeItem = it }
            }
        }
        lastSelectedUsageItem = selectedItem
    }

    override fun onViewCreated(viewLifecycleOwner: LifecycleOwner) {
        this.viewLifecycleOwner = viewLifecycleOwner
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                update()
            }
        }
    }

    private suspend fun update() {
        usageDetailsDataList = withContext(Dispatchers.Default) {
            repository.queryDetailsForCycles()
        }
        updateCycleData()
    }

    private fun updateCycleData(selectedItem: CycleAdapter.CycleItem? = null) {
        val displayItems = buildCycleItems()
        cycleUsageData = displayItems.map { it.usageData }
        if (displayItems.isEmpty()) {
            preference.setHasCycles(false)
            onUsageDataUpdated(NetworkUsageDetailsData.AllZero)
            return
        }

        preference.setHasCycles(true)
        preference.setOnItemSelectedListener(cycleListener)
        suppressSelectionChange = true
        cycleAdapter?.updateCycleItems(displayItems.map { it.item })
        val selectedPosition = selectedItem?.let { cycleAdapter?.getPosition(it) ?: -1 }
            ?.takeIf { it >= 0 }
            ?: findPreferredCyclePosition()
        preference.setSelection(selectedPosition)
        suppressSelectionChange = false
        setSelectedCycle(selectedPosition)
    }

    private fun findPreferredCyclePosition(): Int {
        val selectedItem = lastSelectedUsageItem
            ?: preference.getSelectedItem() as? CycleAdapter.CycleItem
        return selectedItem?.let { cycleAdapter?.findNearestPosition(it) } ?: 0
    }

    private fun buildCycleItems(): List<CycleDisplayItem> {
        val displayItems = mutableListOf<CycleDisplayItem>()
        usageDetailsDataList.filter { it.totalUsage > 0 }.forEach {
            displayItems += CycleDisplayItem(
                CycleAdapter.CycleItem(mContext, it.range.lower, it.range.upper),
                it,
            )
        }
        if (usageDetailsDataList.none { it.totalUsage > 0 }) {
            displayItems += defaultRangeItem()
        }
        customDateItem?.let { displayItems += CycleDisplayItem(it, null) }
        customRangeItem?.let { displayItems += CycleDisplayItem(it, null) }
        displayItems += pickerItem(
            mContext.getText(R.string.data_usage_select_date),
            CycleAdapter.CycleItem.TYPE_DATE_PICKER,
        )
        displayItems += pickerItem(
            mContext.getText(R.string.data_usage_select_range),
            CycleAdapter.CycleItem.TYPE_RANGE_PICKER,
        )
        return displayItems
    }

    private fun setSelectedCycle(position: Int) {
        val cycleItem = cycleAdapter?.getItem(position) ?: return
        when (cycleItem.type) {
            CycleAdapter.CycleItem.TYPE_DATE_PICKER -> showDatePicker()
            CycleAdapter.CycleItem.TYPE_RANGE_PICKER -> showRangePicker()
            else -> selectUsageItem(position, cycleItem)
        }
    }

    private fun selectUsageItem(position: Int, cycleItem: CycleAdapter.CycleItem) {
        lastSelectedUsageItem = cycleItem
        val usageData = cycleUsageData.getOrNull(position)
        if (usageData != null) {
            usageQueryGeneration++
            onUsageDataUpdated(usageData)
        } else {
            queryUsageForItem(cycleItem)
        }
    }

    private fun queryUsageForItem(cycleItem: CycleAdapter.CycleItem) {
        val owner = viewLifecycleOwner ?: return
        val generation = ++usageQueryGeneration
        val range = Range(cycleItem.start, cycleItem.end)
        owner.lifecycleScope.launch {
            val usageData = withContext(Dispatchers.Default) {
                repository.queryDetailsForRange(range)
            }
            if (generation == usageQueryGeneration) {
                onUsageDataUpdated(usageData)
            }
        }
    }

    private fun showDatePicker() {
        val initialTime = customDateItem?.start
            ?: lastSelectedUsageItem?.start
            ?: dayStart(System.currentTimeMillis())
        showDatePickerDialog(mContext.getText(R.string.data_usage_select_date_title), initialTime) {
            startTime ->
            val endTime = addDays(startTime, 1)
            val selectedItem = CycleAdapter.CycleItem(
                mContext.getString(
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
            mContext.getText(R.string.data_usage_select_range_start_title),
            initialStartTime,
        ) { startTime ->
            val initialEndTime = customRangeItem?.end?.let { it - 1 }
                ?: lastSelectedUsageItem?.end?.let { it - 1 }
                ?: startTime
            showDatePickerDialog(
                mContext.getText(R.string.data_usage_select_range_end_title),
                initialEndTime,
            ) { endTime ->
                val lower = minOf(startTime, endTime)
                val upper = addDays(maxOf(startTime, endTime), 1)
                val selectedItem = CycleAdapter.CycleItem(
                    mContext.getString(
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
            preference.context,
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

    private fun defaultRangeItem(): CycleDisplayItem {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - DEFAULT_RANGE_DAYS * DateUtils.DAY_IN_MILLIS
        val item = CycleAdapter.CycleItem(
            mContext.getText(R.string.data_usage_last_30_days),
            startTime,
            endTime,
            CycleAdapter.CycleItem.TYPE_USAGE,
        )
        return CycleDisplayItem(item, null)
    }

    private fun pickerItem(label: CharSequence, type: Int) = CycleDisplayItem(
        CycleAdapter.CycleItem(label, 0L, 0L, type),
        null,
    )

    private val cycleListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!suppressSelectionChange) {
                setSelectedCycle(position)
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            // ignored
        }
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

    private fun formatDate(time: Long): String = DateUtils.formatDateTime(
        mContext,
        time,
        DATE_FORMAT,
    )

    private fun formatDateRange(startTime: Long, endTime: Long): String = DateUtils.formatDateRange(
        mContext,
        startTime,
        endTime - 1,
        DATE_FORMAT,
    )

    private data class CycleDisplayItem(
        val item: CycleAdapter.CycleItem,
        val usageData: NetworkUsageDetailsData?,
    )

    private companion object {
        const val DEFAULT_RANGE_DAYS = 30L

        val DATE_FORMAT = DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_YEAR or
            DateUtils.FORMAT_ABBREV_MONTH
    }
}
