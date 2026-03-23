package com.semantic.ekko.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.qa.QAActivity;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btnSettingsWiseBot).setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), QAActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        view.findViewById(R.id.btnSettingsVault).setOnClickListener(v -> selectBottomTab(R.id.nav_search));
        view.findViewById(R.id.btnSettingsDashboard).setOnClickListener(v -> selectBottomTab(R.id.nav_home));
    }

    private void selectBottomTab(int tabId) {
        if (getActivity() == null) return;
        BottomNavigationView nav = getActivity().findViewById(R.id.bottomNav);
        if (nav != null) {
            nav.setSelectedItemId(tabId);
        }
    }
}
