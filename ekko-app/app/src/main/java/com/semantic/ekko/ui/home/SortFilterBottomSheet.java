package com.semantic.ekko.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        void onApply(String sortOrder, String fileType, String viewMode);
    }

    private final String currentSortOrder;
    private final String currentFileType;
    private final String currentViewMode;
    private final OnApplyListener listener;
    private boolean suppressApply = false;

    public SortFilterBottomSheet(
        String currentSortOrder,
        String currentFileType,
        String currentViewMode,
        OnApplyListener listener
    ) {
        this.currentSortOrder = currentSortOrder;
        this.currentFileType = currentFileType;
        this.currentViewMode = currentViewMode;
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
        RadioGroup radioView = view.findViewById(R.id.radioGroupView);
        ChipGroup chipGroupFileType = view.findViewById(R.id.chipGroupFileType);
        MaterialButton btnReset = view.findViewById(R.id.btnReset);

        suppressApply = true;
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

        if ("list".equals(currentViewMode)) {
            radioView.check(R.id.radioViewList);
        } else if ("folders".equals(currentViewMode)) {
            radioView.check(R.id.radioViewFolders);
        } else {
            radioView.check(R.id.radioViewGrouped);
        }
        suppressApply = false;

        radioSort.setOnCheckedChangeListener((group, checkedId) ->
            dispatchSelection(radioSort, radioView, chipGroupFileType)
        );
        radioView.setOnCheckedChangeListener((group, checkedId) ->
            dispatchSelection(radioSort, radioView, chipGroupFileType)
        );
        chipGroupFileType.setOnCheckedStateChangeListener((group, checkedIds) ->
            dispatchSelection(radioSort, radioView, chipGroupFileType)
        );

        btnReset.setOnClickListener(v -> {
            suppressApply = true;
            restoreInitialState(radioSort, radioView, chipGroupFileType);
            suppressApply = false;
            dispatchSelection(radioSort, radioView, chipGroupFileType);
        });
    }

    private void restoreInitialState(
        RadioGroup radioSort,
        RadioGroup radioView,
        ChipGroup chipGroupFileType
    ) {
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

        chipGroupFileType.clearCheck();
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
            default:
                break;
        }

        if ("list".equals(currentViewMode)) {
            radioView.check(R.id.radioViewList);
        } else if ("folders".equals(currentViewMode)) {
            radioView.check(R.id.radioViewFolders);
        } else {
            radioView.check(R.id.radioViewGrouped);
        }
    }

    private void dispatchSelection(
        RadioGroup radioSort,
        RadioGroup radioView,
        ChipGroup chipGroupFileType
    ) {
        if (suppressApply || listener == null) return;
        String sortOrder = getSortOrder(radioSort.getCheckedRadioButtonId());
        String fileType = getFileType(chipGroupFileType);
        String viewMode = getViewMode(radioView.getCheckedRadioButtonId());
        listener.onApply(sortOrder, fileType, viewMode);
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

    private String getViewMode(int checkedId) {
        if (checkedId == R.id.radioViewGrouped) return "grouped";
        if (checkedId == R.id.radioViewFolders) return "folders";
        return "list";
    }
}
