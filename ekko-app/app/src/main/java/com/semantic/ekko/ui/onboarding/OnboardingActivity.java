package com.semantic.ekko.ui.onboarding;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ui.main.MainActivity;
import com.semantic.ekko.util.FileUtils;
import com.semantic.ekko.util.PrefsManager;
import com.semantic.ekko.util.StorageAccessHelper;
import com.semantic.ekko.work.BackgroundIndexWorker;
import java.util.Arrays;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private MaterialButton btnSecondaryAction;
    private TextView btnSkip;
    private TextView txtPageCounter;
    private LinearLayout indicatorLayout;
    private View layoutSecondaryAction;
    private List<OnboardingPagerAdapter.OnboardingPage> pages;
    private PrefsManager prefsManager;
    private FolderRepository folderRepository;
    private boolean hasRequiredFolder = false;
    private final ActivityResultLauncher<Uri> folderPicker =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) {
                    btnNext.setEnabled(true);
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (SecurityException | IllegalArgumentException e) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (SecurityException | IllegalArgumentException ignored) {
                        btnNext.setEnabled(true);
                        return;
                    }
                }

                FolderEntity folder = new FolderEntity(
                    uri.toString(),
                    FileUtils.getFolderDisplayPath(uri)
                );
                folderRepository.insertIfNotExists(
                    folder,
                    (id, alreadyExists) ->
                        runOnUiThread(() -> {
                            hasRequiredFolder = true;
                            BackgroundIndexWorker.enqueueAll(this);
                            btnNext.setEnabled(true);
                            if (btnSecondaryAction != null) {
                                btnSecondaryAction.setEnabled(true);
                            }
                            updateUiForPage(viewPager.getCurrentItem());
                        })
                );
            }
        );
    private final ActivityResultLauncher<Intent> allFilesAccessLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (StorageAccessHelper.hasAllFilesAccess()) {
                    refreshRequirementState();
                    btnNext.setEnabled(true);
                    if (btnSecondaryAction != null) {
                        btnSecondaryAction.setEnabled(true);
                    }
                    return;
                }
                btnNext.setEnabled(true);
                if (btnSecondaryAction != null) {
                    btnSecondaryAction.setEnabled(true);
                }
                Snackbar.make(
                    btnNext,
                    "Allow full storage access to index shared folders at once.",
                    Snackbar.LENGTH_LONG
                ).show();
                updateUiForPage(viewPager.getCurrentItem());
            }
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);

        prefsManager = new PrefsManager(this);
        folderRepository = new FolderRepository(this);
        if (prefsManager.isOnboardingDone()) {
            launchMain();
            return;
        }

        setContentView(R.layout.activity_onboarding);
        bindViews();
        buildPages();
        applyWindowInsets();

        viewPager.setAdapter(new OnboardingPagerAdapter(pages));
        folderRepository
            .getAllLive()
            .observe(this, folders -> refreshRequirementState());
        createIndicators();
        updateUiForPage(0);

        viewPager.registerOnPageChangeCallback(
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    updateUiForPage(position);
                }
            }
        );

        btnBack.setOnClickListener(v ->
            viewPager.setCurrentItem(viewPager.getCurrentItem() - 1, true)
        );

        btnNext.setOnClickListener(v -> {
            int nextPosition = viewPager.getCurrentItem() + 1;
            if (viewPager.getCurrentItem() == pages.size() - 1) {
                if (!hasRequiredFolder) {
                    btnNext.setEnabled(false);
                    launchFolderImport();
                    return;
                }
                completeOnboarding();
            } else if (nextPosition < pages.size()) {
                viewPager.setCurrentItem(nextPosition, true);
            }
        });

        btnSkip.setOnClickListener(v ->
            viewPager.setCurrentItem(pages.size() - 1, true)
        );

        btnSecondaryAction.setOnClickListener(v -> {
            btnSecondaryAction.setEnabled(false);
            folderPicker.launch(null);
        });

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (viewPager.getCurrentItem() > 0) {
                        viewPager.setCurrentItem(
                            viewPager.getCurrentItem() - 1,
                            true
                        );
                        return;
                    }
                    finish();
                }
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewPager != null) {
            refreshRequirementState();
        }
    }

    private void bindViews() {
        viewPager = findViewById(R.id.viewPagerOnboarding);
        btnBack = findViewById(R.id.btnOnboardingBack);
        btnNext = findViewById(R.id.btnOnboardingNext);
        btnSecondaryAction = findViewById(
            R.id.btnOnboardingSecondaryAction
        );
        btnSkip = findViewById(R.id.btnOnboardingSkip);
        txtPageCounter = findViewById(R.id.txtOnboardingCounter);
        indicatorLayout = findViewById(R.id.layoutIndicators);
        layoutSecondaryAction = findViewById(
            R.id.layoutOnboardingSecondaryAction
        );
    }

    private void buildPages() {
        boolean allFilesMode = StorageAccessHelper.supportsAllFilesAccess();
        pages = Arrays.asList(
            new OnboardingPagerAdapter.OnboardingPage(
                R.string.onboarding_step_one,
                R.string.onboarding_title_1,
                R.string.onboarding_body_1,
                R.string.onboarding_highlight_1,
                R.string.onboarding_tip_1,
                R.drawable.ic_library
            ),
            new OnboardingPagerAdapter.OnboardingPage(
                R.string.onboarding_step_two,
                R.string.onboarding_title_2,
                R.string.onboarding_body_2,
                R.string.onboarding_highlight_2,
                R.string.onboarding_tip_2,
                R.drawable.ic_chat
            ),
            new OnboardingPagerAdapter.OnboardingPage(
                R.string.onboarding_step_three,
                R.string.onboarding_title_3,
                R.string.onboarding_body_3,
                R.string.onboarding_highlight_3,
                R.string.onboarding_tip_3,
                R.drawable.ic_folder_open
            ),
            new OnboardingPagerAdapter.OnboardingPage(
                R.string.onboarding_step_four,
                allFilesMode
                    ? R.string.onboarding_title_4_storage
                    : R.string.onboarding_title_4_folder,
                allFilesMode
                    ? R.string.onboarding_body_4_storage
                    : R.string.onboarding_body_4_folder,
                allFilesMode
                    ? R.string.onboarding_highlight_4_storage
                    : R.string.onboarding_highlight_4_folder,
                allFilesMode
                    ? R.string.onboarding_tip_4_storage
                    : R.string.onboarding_tip_4_folder,
                R.drawable.ic_folder_open
            )
        );
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.onboardingRoot);
        int baseTop = getResources().getDimensionPixelSize(
            R.dimen.onboarding_top_spacing
        );
        int baseBottom = getResources().getDimensionPixelSize(
            R.dimen.onboarding_bottom_spacing
        );

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

    private void createIndicators() {
        indicatorLayout.removeAllViews();
        for (int i = 0; i < pages.size(); i++) {
            ImageView indicator = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize(
                    R.dimen.onboarding_indicator_width
                ),
                getResources().getDimensionPixelSize(
                    R.dimen.onboarding_indicator_height
                )
            );
            params.setMarginEnd(
                getResources().getDimensionPixelSize(
                    R.dimen.onboarding_indicator_spacing
                )
            );
            indicator.setLayoutParams(params);
            indicator.setImageResource(
                R.drawable.bg_onboarding_indicator_inactive
            );
            indicatorLayout.addView(indicator);
        }
    }

    private void updateUiForPage(int position) {
        txtPageCounter.setText(
            getString(
                R.string.onboarding_page_counter,
                position + 1,
                pages.size()
            )
        );

        for (int i = 0; i < indicatorLayout.getChildCount(); i++) {
            ImageView indicator = (ImageView) indicatorLayout.getChildAt(i);
            indicator.setImageResource(
                i == position
                    ? R.drawable.bg_onboarding_indicator_active
                    : R.drawable.bg_onboarding_indicator_inactive
            );
        }

        boolean firstPage = position == 0;
        boolean lastPage = position == pages.size() - 1;

        btnBack.setVisibility(firstPage ? View.INVISIBLE : View.VISIBLE);
        btnSkip.setVisibility(lastPage ? View.INVISIBLE : View.VISIBLE);
        btnSkip.setText(R.string.onboarding_skip_setup);
        layoutSecondaryAction.setVisibility(
            lastPage && StorageAccessHelper.supportsAllFilesAccess()
                ? View.VISIBLE
                : View.GONE
        );
        btnSecondaryAction.setEnabled(true);
        if (lastPage) {
            if (hasRequiredFolder) {
                btnNext.setText(R.string.onboarding_start);
            } else if (StorageAccessHelper.supportsAllFilesAccess()) {
                btnNext.setText(R.string.onboarding_allow_storage_access);
            } else {
                btnNext.setText(R.string.onboarding_choose_folder);
            }
        } else {
            btnNext.setText(R.string.onboarding_next);
        }
        btnNext.setIcon(
            lastPage
                ? null
                : ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_arrow_forward_small
                )
        );
        btnNext.setEnabled(true);
        btnBack.setEnabled(true);
    }

    private void refreshRequirementState() {
        if (folderRepository == null || viewPager == null) {
            return;
        }
        folderRepository.getAll(folders ->
            runOnUiThread(() -> {
                boolean hasFolders = folders != null && !folders.isEmpty();
                hasRequiredFolder =
                    hasFolders ||
                    (
                        StorageAccessHelper.supportsAllFilesAccess() &&
                        StorageAccessHelper.hasAllFilesAccess()
                    );
                updateUiForPage(viewPager.getCurrentItem());
            })
        );
    }

    private void launchFolderImport() {
        if (StorageAccessHelper.supportsAllFilesAccess()) {
            if (StorageAccessHelper.hasAllFilesAccess()) {
                prefsManager.setPublicImportPending(true);
                BackgroundIndexWorker.enqueueAll(this);
                refreshRequirementState();
            } else {
                allFilesAccessLauncher.launch(
                    StorageAccessHelper.createManageAllFilesAccessIntent(this)
                );
            }
            return;
        }
        btnSecondaryAction.setEnabled(false);
        folderPicker.launch(null);
    }

    private void completeOnboarding() {
        if (
            StorageAccessHelper.supportsAllFilesAccess() &&
            StorageAccessHelper.hasAllFilesAccess()
        ) {
            prefsManager.setPublicImportPending(true);
            BackgroundIndexWorker.enqueueAll(this);
        }
        prefsManager.setOnboardingDone(true);
        launchMain();
    }

    private void launchMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );
        startActivity(intent);
        finish();
    }
}
