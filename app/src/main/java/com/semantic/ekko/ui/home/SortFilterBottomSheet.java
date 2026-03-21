package com.semantic.ekko.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.semantic.ekko.R;

public class SortFilterBottomSheet extends BottomSheetDialogFragment {

    public interface OnApplyListener {
        void onApply(String sortOrder, String fileType);
    }

    private final String currentSortOrder;
    private final String currentFileType;
    private final OnApplyListener listener;

    public SortFilterBottomSheet(
        String currentSortOrder,
        String currentFileType,
        OnApplyListener listener
    ) {
        this.currentSortOrder = currentSortOrder;
        this.currentFileType = currentFileType;
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(
            R.layout.bottom_sheet_sort_filter,
            container,
            false
        );
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        RadioGroup radioSort = view.findViewById(R.id.radioGroupSort);
        ChipGroup chipGroupFileType = view.findViewById(R.id.chipGroupFileType);
        MaterialButton btnApply = view.findViewById(R.id.btnApply);
        MaterialButton btnReset = view.findViewById(R.id.btnReset);

        // Preselect sort order
        switch (currentSortOrder) {
            case "name":
                radioSort.check(R.id.radioSortName);
                break;
            case "word_count":
                radioSort.check(R.id.radioSortWordCount);
                break;
            case "read_time":
                radioSort.check(R.id.radioSortReadTime);
                break;
            default:
                radioSort.check(R.id.radioSortRecent);
                break;
        }

        // Preselect file type
        switch (currentFileType) {
            case "pdf":
                checkChip(chipGroupFileType, R.id.chipPdf);
                break;
            case "docx":
                checkChip(chipGroupFileType, R.id.chipDocx);
                break;
            case "pptx":
                checkChip(chipGroupFileType, R.id.chipPptx);
                break;
            case "txt":
                checkChip(chipGroupFileType, R.id.chipTxt);
                break;
        }

        btnApply.setOnClickListener(v -> {
            String sortOrder = getSortOrder(
                radioSort.getCheckedRadioButtonId()
            );
            String fileType = getFileType(chipGroupFileType);
            if (listener != null) listener.onApply(sortOrder, fileType);
            dismiss();
        });

        btnReset.setOnClickListener(v -> {
            if (listener != null) listener.onApply("recent", "all");
            dismiss();
        });
    }

    private void checkChip(ChipGroup group, int chipId) {
        Chip chip = group.findViewById(chipId);
        if (chip != null) chip.setChecked(true);
    }

    private String getSortOrder(int checkedId) {
        if (checkedId == R.id.radioSortName) return "name";
        if (checkedId == R.id.radioSortWordCount) return "word_count";
        if (checkedId == R.id.radioSortReadTime) return "read_time";
        return "recent";
    }

    private String getFileType(ChipGroup group) {
        int checkedId = group.getCheckedChipId();
        if (checkedId == R.id.chipPdf) return "pdf";
        if (checkedId == R.id.chipDocx) return "docx";
        if (checkedId == R.id.chipPptx) return "pptx";
        if (checkedId == R.id.chipTxt) return "txt";
        return "all";
    }
}
