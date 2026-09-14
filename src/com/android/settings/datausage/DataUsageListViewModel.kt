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

import android.app.Application
import android.net.NetworkTemplate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.settings.datausage.lib.NetworkCycleBucketRepository
import com.android.settings.datausage.lib.NetworkStatsRepository
import com.android.settings.datausage.lib.NetworkStatsRepository.Companion.Bucket
import com.android.settings.datausage.lib.NetworkStatsRepository.Companion.aggregate
import com.android.settings.datausage.lib.NetworkStatsRepository.Companion.filterTime
import com.android.settings.datausage.lib.NetworkUsageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

data class SelectedBuckets(
    val selectedCycle: NetworkUsageData,
    val buckets: List<Bucket>,
)

class DataUsageListViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = viewModelScope + Dispatchers.Default

    val templateFlow = MutableStateFlow<NetworkTemplate?>(null)

    private val bucketsFlow = templateFlow.filterNotNull().map { template ->
        NetworkStatsRepository(getApplication(), template).queryDetailsForDevice()
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    val cyclesFlow = combine(templateFlow.filterNotNull(), bucketsFlow) { template, buckets ->
        NetworkCycleBucketRepository(application, template, buckets).loadCycles()
    }.flowOn(Dispatchers.Default)

    val selectedCycleFlow = MutableStateFlow<NetworkUsageData?>(null)

    private val selectedBucketsFlow =
        combine(
            templateFlow.filterNotNull(),
            selectedCycleFlow.filterNotNull(),
            bucketsFlow,
        ) { template, selectedCycle, buckets ->
            val selectedBuckets = buckets.filterTime(selectedCycle.startTime, selectedCycle.endTime)
            val bucketUsage = selectedBuckets.aggregate()?.usage ?: selectedCycle.usage
            val summaryUsage = NetworkStatsRepository(getApplication(), template)
                .querySummaryForDevice(selectedCycle.startTime, selectedCycle.endTime)
            SelectedBuckets(
                selectedCycle = selectedCycle.copy(usage = maxOf(summaryUsage, bucketUsage)),
                buckets = selectedBuckets,
            )
        }.flowOn(Dispatchers.Default)

    val selectedUsageDataFlow =
        selectedBucketsFlow.map { it.selectedCycle }.flowOn(Dispatchers.Default)

}
