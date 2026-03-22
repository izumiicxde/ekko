package com.semantic.ekko.ui.main;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.home.HomeFragment;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.ui.search.SearchFragment;
import com.semantic.ekko.ui.settings.SettingsFragment;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_HOME = "home";
    private static final String TAG_SEARCH = "search";
    private static final String TAG_SETTINGS = "settings";

    private BottomNavigationView bottomNav;
    private String currentTag = TAG_HOME;
    private boolean ignoreNavSelection = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottomNav);

        if (savedInstanceState == null) {
            showFragment(TAG_HOME);
        } else {
            currentTag = savedInstanceState.getString("currentTag", TAG_HOME);
        }

        bottomNav.setOnItemSelectedListener(item -> {
            if (ignoreNavSelection) return true;

            int id = item.getItemId();
            if (id == R.id.nav_home) {
                showFragment(TAG_HOME);
            } else if (id == R.id.nav_search) {
                showFragment(TAG_SEARCH);
            } else if (id == R.id.nav_ask) {
                Intent intent = new Intent(this, QAActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
                // Immediately revert nav selection back without triggering listener
                syncNavToCurrentTag();
                return true;
            } else if (id == R.id.nav_settings) {
                showFragment(TAG_SETTINGS);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync nav bar to current fragment when returning from QAActivity
        syncNavToCurrentTag();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("currentTag", currentTag);
    }

    // =========================
    // NAV SYNC
    // =========================

    /**
     * Updates the bottom nav selected item to match currentTag
     * without triggering the OnItemSelectedListener.
     */
    private void syncNavToCurrentTag() {
        int expectedItem = tagToNavId(currentTag);
        if (bottomNav.getSelectedItemId() != expectedItem) {
            ignoreNavSelection = true;
            bottomNav.setSelectedItemId(expectedItem);
            ignoreNavSelection = false;
        }
    }

    private int tagToNavId(String tag) {
        switch (tag) {
            case TAG_SEARCH:
                return R.id.nav_search;
            case TAG_SETTINGS:
                return R.id.nav_settings;
            default:
                return R.id.nav_home;
        }
    }

    // =========================
    // FRAGMENT SWITCHING
    // =========================

    private void showFragment(String tag) {
        if (
            tag.equals(currentTag) &&
            getSupportFragmentManager().findFragmentByTag(tag) != null
        ) {
            return;
        }
        currentTag = tag;

        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment == null) {
            fragment = createFragment(tag);
        }

        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();

        for (Fragment f : getSupportFragmentManager().getFragments()) {
            tx.hide(f);
        }

        if (fragment.isAdded()) {
            tx.show(fragment);
        } else {
            tx.add(R.id.fragmentContainer, fragment, tag);
        }

        tx.commit();
    }

    private Fragment createFragment(String tag) {
        switch (tag) {
            case TAG_SEARCH:
                return new SearchFragment();
            case TAG_SETTINGS:
                return new SettingsFragment();
            default:
                return new HomeFragment();
        }
    }
}
