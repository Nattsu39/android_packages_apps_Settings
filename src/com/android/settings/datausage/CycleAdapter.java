/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.settings.datausage;

import android.content.Context;
import android.util.Range;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settings.Utils;
import com.android.settingslib.widget.SettingsSpinnerAdapter;

import java.util.ArrayList;
import java.util.List;

public class CycleAdapter extends SettingsSpinnerAdapter<CycleAdapter.CycleItem> {

    private final SpinnerInterface mSpinner;

    public CycleAdapter(Context context, SpinnerInterface spinner) {
        super(context);
        mSpinner = spinner;
        mSpinner.setAdapter(this);
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView,
            @NonNull ViewGroup parent) {
        if (parent instanceof Spinner) {
            setSelectedPosition(((Spinner) parent).getSelectedItemPosition());
        }
        return super.getDropDownView(position, convertView, parent);
    }

    /**
     * Find position of {@link CycleItem} in this adapter which is nearest
     * the given {@link CycleItem}.
     */
    public int findNearestPosition(CycleItem target) {
        if (target != null && target.getType() == CycleItem.TYPE_USAGE) {
            final int count = getCount();
            for (int i = 0; i < count; i++) {
                final CycleItem item = getItem(i);
                if (target.equals(item)) {
                    return i;
                }
            }
            for (int i = count - 1; i >= 0; i--) {
                final CycleItem item = getItem(i);
                if (item.getType() == CycleItem.TYPE_USAGE && item.compareTo(target) >= 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    void setInitialCycleList(List<Long> cycles, long selectedCycle) {
        clear();
        for (int i = 0; i < cycles.size() - 1; i++) {
            add(new CycleAdapter.CycleItem(getContext(), cycles.get(i + 1), cycles.get(i)));
            if (cycles.get(i) == selectedCycle) {
                mSpinner.setSelection(i);
            }
        }
    }

    /**
     * Rebuild list based on network data. Always selects the newest item,
     * updating the inspection range on chartData.
     */
    public void updateCycleList(List<Range<Long>> cycleData) {
        final Context context = getContext();
        final List<CycleItem> cycleItems = new ArrayList<>();
        for (Range<Long> cycle : cycleData) {
            cycleItems.add(new CycleItem(context, cycle.getLower(), cycle.getUpper()));
        }
        updateCycleItems(cycleItems);
    }

    /**
     * Rebuild list with already formatted cycle items.
     */
    public void updateCycleItems(List<CycleItem> cycleItems) {
        // stash away currently selected cycle to try restoring below
        final CycleAdapter.CycleItem previousItem = (CycleAdapter.CycleItem)
                mSpinner.getSelectedItem();
        clear();

        for (CycleItem cycleItem : cycleItems) {
            add(cycleItem);
        }

        // force pick the current cycle (first item)
        if (getCount() > 0) {
            final int position = findNearestPosition(previousItem);
            mSpinner.setSelection(position);
        }
    }

    /**
     * List item that reflects a specific data usage cycle.
     */
    public static class CycleItem implements Comparable<CycleItem> {
        public static final int TYPE_USAGE = 0;
        public static final int TYPE_DATE_PICKER = 1;
        public static final int TYPE_RANGE_PICKER = 2;

        public CharSequence label;
        public long start;
        public long end;
        private final int mType;

        public CycleItem(Context context, long start, long end) {
            this(Utils.formatDateRange(context, start, end), start, end, TYPE_USAGE);
        }

        public CycleItem(CharSequence label, long start, long end, int type) {
            this.label = label;
            this.start = start;
            this.end = end;
            mType = type;
        }

        public int getType() {
            return mType;
        }

        @Override
        public String toString() {
            return label.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof CycleItem) {
                final CycleItem another = (CycleItem) o;
                return start == another.start && end == another.end && mType == another.mType;
            }
            return false;
        }

        @Override
        public int compareTo(CycleItem another) {
            return Long.compare(start, another.start);
        }
    }

    public interface SpinnerInterface {
        void setAdapter(CycleAdapter cycleAdapter);

        Object getSelectedItem();

        void setSelection(int position);
    }
}
