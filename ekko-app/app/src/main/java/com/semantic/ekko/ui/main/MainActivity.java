package com.semantic.ekko.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ui.home.HomeFragment;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.ui.search.SearchFragment;
import com.semantic.ekko.ui.settings.SettingsFragment;
import com.semantic.ekko.util.PrefsManager;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_HOME = "home";
    private static final String TAG_SEARCH = "search";
    private static final String TAG_SETTINGS = "settings";

    private LinearLayout navHome;
    private LinearLayout navSearch;
    private LinearLayout navAsk;
    private LinearLayout navSettings;
    private String currentTag = TAG_HOME;
    private FolderRepository folderRepository;
    private PrefsManager prefsManager;
    private boolean hasIncludedFolders = false;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
        (sharedPreferences, key) -> refreshFolderAvailability();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindNavViews();
        applyWindowInsets();
        folderRepository = new FolderRepository(this);
        prefsManager = new PrefsManager(this);
        prefsManager.registerListener(prefsListener);
        folderRepository
            .getAllLive()
            .observe(this, folders -> refreshFolderAvailability());

        if (savedInstanceState == null) {
            showFragment(TAG_HOME);
        } else {
            currentTag = savedInstanceState.getString("currentTag", TAG_HOME);
            syncNavToCurrentTag();
        }

        navHome.setOnClickListener(v -> {
            showFragment(TAG_HOME);
            syncNavToCurrentTag();
        });

        navSearch.setOnClickListener(v -> {
            if (!hasIncludedFolders) {
                showNoFoldersMessage();
                return;
            }
            showFragment(TAG_SEARCH);
            syncNavToCurrentTag();
        });

        navAsk.setOnClickListener(v -> {
            if (!hasIncludedFolders) {
                showNoFoldersMessage();
                return;
            }
            Intent intent = new Intent(this, QAActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            syncNavToCurrentTag();
        });

        navSettings.setOnClickListener(v -> {
            showFragment(TAG_SETTINGS);
            syncNavToCurrentTag();
        });

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Fragment currentFragment =
                        getSupportFragmentManager().findFragmentByTag(
                            currentTag
                        );

                    if (
                        currentFragment instanceof HomeFragment &&
                        (
                            (HomeFragment) currentFragment
                        ).handleSystemBackPressed()
                    ) {
                        return;
                    }

                    if (!TAG_HOME.equals(currentTag)) {
                        showFragment(TAG_HOME);
                        syncNavToCurrentTag();
                        return;
                    }

                    moveTaskToBack(true);
                }
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFolderAvailability();
        syncNavToCurrentTag();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("currentTag", currentTag);
    }

    @Override
    protected void onDestroy() {
        if (prefsManager != null) {
            prefsManager.unregisterListener(prefsListener);
        }
        super.onDestroy();
    }

    private void bindNavViews() {
        navHome = findViewById(R.id.navHome);
        navSearch = findViewById(R.id.navSearch);
        navAsk = findViewById(R.id.navAsk);
        navSettings = findViewById(R.id.navSettings);
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.mainRoot);
        int baseTop = root.getPaddingTop();
        int baseBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            (view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                );
                view.setPadding(
                    view.getPaddingLeft(),
                    baseTop + insets.top,
                    view.getPaddingRight(),
                    baseBottom + insets.bottom
                );
                return windowInsets;
            }
        );
    }

    private void syncNavToCurrentTag() {
        setNavSelected(navHome, TAG_HOME.equals(currentTag), true);
        setNavSelected(
            navSearch,
            TAG_SEARCH.equals(currentTag),
            hasIncludedFolders
        );
        setNavSelected(navAsk, false, hasIncludedFolders);
        setNavSelected(navSettings, TAG_SETTINGS.equals(currentTag), true);
    }

    private void setNavSelected(
        LinearLayout item,
        boolean selected,
        boolean enabled
    ) {
        int activeColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary,
            0
        );
        int inactiveColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0
        );

        item.setBackgroundResource(
            selected
                ? R.drawable.bg_bottom_nav_selected
                : android.R.color.transparent
        );
        item.setAlpha(enabled ? 1f : 0.38f);
        item.setEnabled(enabled);
        item
            .animate()
            .scaleX(selected ? 1f : 0.94f)
            .scaleY(selected ? 1f : 0.94f)
            .translationY(selected ? 0f : 1.5f)
            .setDuration(180)
            .start();

        for (int i = 0; i < item.getChildCount(); i++) {
            View child = item.getChildAt(i);
            if (child instanceof ImageView) {
                ((ImageView) child).setColorFilter(
                    selected ? activeColor : inactiveColor
                );
                child
                    .animate()
                    .alpha(enabled ? 1f : 0.38f)
                    .scaleX(selected ? 1f : 0.92f)
                    .scaleY(selected ? 1f : 0.92f)
                    .setDuration(180)
                    .start();
            } else if (child instanceof TextView) {
                ((TextView) child).setTextColor(
                    selected ? activeColor : inactiveColor
                );
                child
                    .animate()
                    .alpha(enabled ? 1f : 0.68f)
                    .translationY(selected ? 0f : -1f)
                    .setDuration(180)
                    .start();
            }
        }
    }

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
        tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);

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

    private void applySavedTheme() {
        PrefsManager prefs = new PrefsManager(this);
        String theme = prefs.getTheme();
        int mode;
        if ("light".equals(theme)) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(theme)) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    public void navigateToSettings() {
        showFragment(TAG_SETTINGS);
        syncNavToCurrentTag();
    }

    private void refreshFolderAvailability() {
        folderRepository.getAll(folders -> {
            List<FolderEntity> safeFolders =
                folders == null ? Collections.emptyList() : folders;
            Set<String> excluded = prefsManager.getExcludedFolderUris();
            boolean included = false;
            for (FolderEntity folder : safeFolders) {
                if (!excluded.contains(folder.uri)) {
                    included = true;
                    break;
                }
            }
            boolean finalIncluded = included;
            runOnUiThread(() -> {
                hasIncludedFolders = finalIncluded;
                if (!finalIncluded && TAG_SEARCH.equals(currentTag)) {
                    showFragment(TAG_HOME);
                }
                syncNavToCurrentTag();
            });
        });
    }

    private void showNoFoldersMessage() {
        Snackbar.make(
            findViewById(R.id.fragmentContainer),
            "No folders selected. Add one in Settings first.",
            Snackbar.LENGTH_SHORT
        ).show();
    }
}
