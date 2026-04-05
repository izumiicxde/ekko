package com.semantic.ekko.ui.detail;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.R;
import com.semantic.ekko.data.model.DocumentEntity;
import com.semantic.ekko.data.model.FolderEntity;
import com.semantic.ekko.data.repository.FolderRepository;
import com.semantic.ekko.ml.EntityExtractorHelper;
import com.semantic.ekko.ui.pdf.PdfViewerActivity;
import com.semantic.ekko.ui.qa.QAActivity;
import com.semantic.ekko.util.FileUtils;
import com.semantic.ekko.util.UserFacingMessages;
import io.noties.markwon.Markwon;
import java.util.ArrayList;
import java.util.List;

public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";

    private DetailViewModel viewModel;
    private FolderRepository folderRepository;
    private DocumentEntity currentDoc;

    private TextView txtDocName;
    private TextView txtFileType;
    private TextView txtSummary;
    private TextView txtWordCount;
    private TextView txtReadTime;
    private TextView txtEntityCount;
    private Chip chipCategory;
    private ChipGroup chipGroupKeywords;
    private ChipGroup chipGroupEntities;
    private ChipGroup chipGroupCorrection;
    private MaterialButton btnOpenFile;
    private MaterialButton btnChatWithFile;
    private MaterialButton btnEnhanceSummary;
    private View labelEntities;
    private View progressEnhanceSummary;
    private View layoutSummaryLoading;
    private TextView btnToggleSummary;
    private TextView btnToggleEntities;
    private TextView txtSummaryHint;
    private Markwon markwon;
    private boolean mlReady = false;
    private boolean backendReady = false;
    private boolean summaryExpanded = false;
    private boolean entitiesExpanded = false;
    private List<String> currentEntities = new ArrayList<>();
    private DocumentEntity pendingOpenDoc;
    private FolderEntity pendingOpenFolder;
    private final ActivityResultLauncher<Uri> folderAccessLauncher =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) {
                    showOpenFileError();
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
                    } catch (
                        SecurityException | IllegalArgumentException ignored
                    ) {
                        showOpenFileError();
                        return;
                    }
                }
                retryPendingOpen();
            }
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        markwon = Markwon.create(this);
        folderRepository = new FolderRepository(this);

        bindViews();
        applyInsets();
        setupViewModel();
        observeReadiness();

        long docId = getIntent().getLongExtra(EXTRA_DOCUMENT_ID, -1);
        if (docId == -1) {
            finish();
            return;
        }

        viewModel.loadDocument(docId);
    }

    private void bindViews() {
        txtDocName = findViewById(R.id.txtDocName);
        txtFileType = findViewById(R.id.txtFileType);
        txtSummary = findViewById(R.id.txtSummary);
        txtWordCount = findViewById(R.id.txtWordCount);
        txtReadTime = findViewById(R.id.txtReadTime);
        txtEntityCount = findViewById(R.id.txtEntityCount);
        chipCategory = findViewById(R.id.chipCategory);
        chipGroupKeywords = findViewById(R.id.chipGroupKeywords);
        chipGroupEntities = findViewById(R.id.chipGroupEntities);
        chipGroupCorrection = findViewById(R.id.chipGroupCorrection);
        btnOpenFile = findViewById(R.id.btnOpenFile);
        btnChatWithFile = findViewById(R.id.btnChatWithFile);
        btnEnhanceSummary = findViewById(R.id.btnEnhanceSummary);
        labelEntities = findViewById(R.id.labelEntities);
        progressEnhanceSummary = findViewById(R.id.progressEnhanceSummary);
        layoutSummaryLoading = findViewById(R.id.layoutSummaryLoading);
        btnToggleSummary = findViewById(R.id.btnToggleSummary);
        btnToggleEntities = findViewById(R.id.btnToggleEntities);
        txtSummaryHint = findViewById(R.id.txtSummaryHint);
        btnToggleSummary.setOnClickListener(v -> toggleSummary());
        btnToggleEntities.setOnClickListener(v -> toggleEntities());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void applyInsets() {
        View content = findViewById(R.id.detailContent);
        int baseTop = content.getPaddingTop();
        int baseBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            );
            view.setPadding(
                view.getPaddingLeft(),
                baseTop + systemBars.top,
                view.getPaddingRight(),
                baseBottom + systemBars.bottom
            );
            return insets;
        });
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(DetailViewModel.class);

        viewModel
            .getDocument()
            .observe(this, doc -> {
                if (doc != null) {
                    currentDoc = doc;
                    bindDocument(doc);
                }
            });

        viewModel
            .getErrorMessage()
            .observe(this, msg -> {
                if (msg != null) Snackbar.make(
                    btnOpenFile,
                    msg,
                    Snackbar.LENGTH_LONG
                ).show();
            });

        viewModel
            .getAiSummary()
            .observe(this, summary -> {
                if (summary != null && !summary.isEmpty()) {
                    if (currentDoc != null) currentDoc.summary = summary;
                    txtSummary.setVisibility(View.VISIBLE);
                    txtSummaryHint.setText(
                        "Freshly generated from your indexed document text"
                    );
                    summaryExpanded = false;
                    renderMarkdownSummary(summary);
                    updateSummaryToggle(summary);
                    btnEnhanceSummary.setText("Refresh summary");
                    btnEnhanceSummary.setEnabled(true);
                }
            });

        viewModel
            .getSummaryLoading()
            .observe(this, loading -> {
                if (loading == null) return;
                progressEnhanceSummary.setVisibility(
                    loading ? View.VISIBLE : View.GONE
                );
                layoutSummaryLoading.setVisibility(
                    loading ? View.VISIBLE : View.GONE
                );
                btnEnhanceSummary.setEnabled(
                    mlReady && backendReady && !loading
                );
                btnEnhanceSummary.setText(
                    loading ? "Creating summary..." : getSummaryActionText()
                );
                if (loading) {
                    txtSummaryHint.setText(
                        "Reading indexed content and drafting a tighter overview"
                    );
                    renderMarkdownSummary(
                        "Building a concise summary. This usually takes a few seconds."
                    );
                }
            });
    }

    private void observeReadiness() {
        EkkoApp app = EkkoApp.getInstance();
        app
            .getMlReadyState()
            .observe(this, ready -> {
                mlReady = ready != null && ready;
                updateActionReadiness();
            });
        app
            .getBackendReachableState()
            .observe(this, ready -> {
                backendReady = ready != null && ready;
                updateActionReadiness();
            });
        app
            .getSummaryState()
            .observe(this, state -> {
                if (
                    state == null ||
                    currentDoc == null ||
                    state.documentId != currentDoc.id
                ) {
                    return;
                }
                if (state.loading) {
                    progressEnhanceSummary.setVisibility(View.VISIBLE);
                    layoutSummaryLoading.setVisibility(View.VISIBLE);
                    btnEnhanceSummary.setEnabled(false);
                    btnEnhanceSummary.setText("Creating summary...");
                    txtSummaryHint.setText(
                        "Generating in the background. You can leave this page and come back."
                    );
                    return;
                }

                progressEnhanceSummary.setVisibility(View.GONE);
                layoutSummaryLoading.setVisibility(View.GONE);
                btnEnhanceSummary.setEnabled(mlReady && backendReady);
                btnEnhanceSummary.setText(getSummaryActionText());

                if (state.summary != null && !state.summary.trim().isEmpty()) {
                    currentDoc.summary = state.summary;
                    summaryExpanded = false;
                    renderMarkdownSummary(state.summary);
                    updateSummaryToggle(state.summary);
                    txtSummaryHint.setText(
                        "Freshly generated from your indexed document text"
                    );
                    viewModel.loadDocument(currentDoc.id);
                }
            });
        app.refreshBackendHealthAsync();
        updateActionReadiness();
    }

    private void updateActionReadiness() {
        boolean ready = mlReady;
        boolean loading =
            viewModel != null &&
            Boolean.TRUE.equals(viewModel.getSummaryLoading().getValue());
        if (btnEnhanceSummary != null) {
            btnEnhanceSummary.setEnabled(ready && !loading);
        }
        if (btnChatWithFile != null) {
            btnChatWithFile.setEnabled(ready);
            btnChatWithFile.setAlpha(ready ? 1f : 0.55f);
        }
        if (
            !ready &&
            txtSummaryHint != null &&
            (currentDoc == null ||
                currentDoc.summary == null ||
                currentDoc.summary.isEmpty())
        ) {
            txtSummaryHint.setText(
                !mlReady
                    ? "Summary tools will unlock after local models finish loading"
                    : "Summary tools are waiting for the backend connection"
            );
        } else if (
            ready &&
            !backendReady &&
            txtSummaryHint != null &&
            (currentDoc == null ||
                currentDoc.summary == null ||
                currentDoc.summary.isEmpty())
        ) {
            txtSummaryHint.setText(
                "Backend health looks stale, but summary requests can still be attempted"
            );
        }
    }

    private void bindDocument(DocumentEntity doc) {
        txtDocName.setText(doc.name);
        txtFileType.setText(
            doc.fileType != null ? doc.fileType.toUpperCase() : "FILE"
        );
        chipCategory.setText(doc.category != null ? doc.category : "General");
        styleCategoryChip(chipCategory);

        String wordLabel =
            doc.wordCount >= 1000
                ? String.format("%.1fk", doc.wordCount / 1000f)
                : String.valueOf(doc.wordCount);
        txtWordCount.setText(wordLabel);
        txtReadTime.setText(String.valueOf(Math.max(1, doc.wordCount / 200)));

        if (doc.summary != null && !doc.summary.isEmpty()) {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummaryHint.setText("Saved summary from the indexed document");
            summaryExpanded = false;
            renderMarkdownSummary(doc.summary);
            updateSummaryToggle(doc.summary);
            btnEnhanceSummary.setText("Refresh summary");
        } else {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummaryHint.setText(
                "Create a quick overview from the indexed text already stored on-device"
            );
            summaryExpanded = false;
            renderMarkdownSummary(
                "No summary yet. Use the action above to create one."
            );
            updateSummaryToggle("");
            btnEnhanceSummary.setText("Create summary");
        }

        chipGroupKeywords.removeAllViews();
        if (doc.keywords != null && !doc.keywords.isEmpty()) {
            for (String kw : doc.keywords.split(",")) {
                String trimmed = kw.trim();
                if (trimmed.isEmpty()) continue;
                Chip chip = new Chip(this);
                chip.setText(trimmed);
                chip.setClickable(false);
                chip.setFocusable(false);
                chip.setTypeface(
                    ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
                );
                styleDisplayChip(chip);
                chipGroupKeywords.addView(chip);
            }
        } else {
            Chip emptyChip = new Chip(this);
            emptyChip.setText("No keywords extracted yet");
            emptyChip.setClickable(false);
            emptyChip.setFocusable(false);
            emptyChip.setTypeface(
                ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
            );
            stylePlaceholderChip(emptyChip);
            chipGroupKeywords.addView(emptyChip);
        }

        chipGroupEntities.removeAllViews();
        currentEntities = EntityExtractorHelper.entitiesFromString(doc.entities);
        txtEntityCount.setText(String.valueOf(currentEntities.size()));

        if (!currentEntities.isEmpty()) {
            labelEntities.setVisibility(View.VISIBLE);
            chipGroupEntities.setVisibility(View.VISIBLE);
            entitiesExpanded = false;
            renderEntityChips();
            updateEntitiesToggle();
        } else {
            labelEntities.setVisibility(View.GONE);
            chipGroupEntities.setVisibility(View.GONE);
            btnToggleEntities.setVisibility(View.GONE);
        }

        preselectCorrection(doc.category);

        chipGroupCorrection.setOnCheckedStateChangeListener(
            (group, checkedIds) -> {
                if (checkedIds.isEmpty() || currentDoc == null) return;
                String selected = chipIdToCategory(checkedIds.get(0));
                if (
                    selected == null || selected.equals(currentDoc.category)
                ) return;
                viewModel.correctCategory(currentDoc, selected);
                currentDoc.category = selected;
                chipCategory.setText(selected);
                styleCategoryChip(chipCategory);
                Snackbar.make(
                    btnOpenFile,
                    "Category updated to " + selected,
                    Snackbar.LENGTH_SHORT
                ).show();
            }
        );

        btnOpenFile.setOnClickListener(v -> openFile(doc));
        btnEnhanceSummary.setOnClickListener(v ->
            viewModel.generateAiSummary()
        );

        btnChatWithFile.setOnClickListener(v -> {
            Intent intent = new Intent(this, QAActivity.class);
            intent.putExtra(QAActivity.EXTRA_DOCUMENT_ID, doc.id);
            intent.putExtra(QAActivity.EXTRA_DOCUMENT_NAME, doc.name);
            startActivity(intent);
        });
    }

    private void openFile(DocumentEntity doc) {
        folderRepository.getById(doc.folderId, folder ->
            runOnUiThread(() -> openResolvedFile(doc, folder))
        );
    }

    private void openResolvedFile(DocumentEntity doc, FolderEntity folder) {
        Uri sourceUri = null;
        Uri openUri = null;
        String mimeType = null;
        try {
            sourceUri = resolveSourceUri(doc, folder);
            mimeType = FileUtils.resolveMimeType(this, sourceUri, doc.name);
            openUri = FileUtils.copyToViewerCache(this, sourceUri, doc.name);

            if (tryOpenInNativeApp(doc.name, openUri, mimeType)) {
                return;
            }

            if (isPdfDocument(mimeType, doc.name)) {
                openPdfFallback(doc.name, openUri);
                return;
            }

            throw new IllegalStateException("No viewer available");
        } catch (SecurityException e) {
            recoverMissingAccess(doc, folder);
        } catch (Exception e) {
            showOpenFileError();
        }
    }

    private Uri resolveSourceUri(DocumentEntity doc, FolderEntity folder) {
        Uri storedUri = Uri.parse(doc.uri);
        if (FileUtils.canReadUri(this, storedUri)) {
            return storedUri;
        }

        Uri fallbackUri = resolveUriFromFolderTree(doc, folder);
        if (fallbackUri != null && FileUtils.canReadUri(this, fallbackUri)) {
            return fallbackUri;
        }

        if (FileUtils.hasPersistedReadPermission(this, storedUri)) {
            return storedUri;
        }

        throw new SecurityException("Missing read permission for source");
    }

    private Uri resolveUriFromFolderTree(DocumentEntity doc, FolderEntity folder) {
        if (folder == null || folder.uri == null || folder.uri.trim().isEmpty()) {
            return null;
        }

        Uri treeUri = Uri.parse(folder.uri);
        if (!FileUtils.hasPersistedReadPermission(this, treeUri)) {
            return null;
        }

        return FileUtils.resolveDocumentUriFromTree(
            this,
            treeUri,
            doc.relativePath
        );
    }

    private void recoverMissingAccess(DocumentEntity doc, FolderEntity folder) {
        pendingOpenDoc = doc;
        pendingOpenFolder = folder;

        if (
            folder != null &&
            folder.uri != null &&
            !folder.uri.trim().isEmpty() &&
            "content".equalsIgnoreCase(Uri.parse(folder.uri).getScheme())
        ) {
            folderAccessLauncher.launch(Uri.parse(folder.uri));
            return;
        }

        showOpenFileError();
    }

    private void retryPendingOpen() {
        if (pendingOpenDoc == null) {
            return;
        }
        DocumentEntity doc = pendingOpenDoc;
        FolderEntity folder = pendingOpenFolder;
        pendingOpenDoc = null;
        pendingOpenFolder = null;
        openResolvedFile(doc, folder);
    }

    private void showOpenFileError() {
        Snackbar.make(
            btnOpenFile,
            UserFacingMessages.FILE_OPEN_UNAVAILABLE,
            Snackbar.LENGTH_LONG
        ).show();
    }

    private boolean tryOpenInNativeApp(
        String docName,
        Uri openUri,
        String mimeType
    ) {
        String[] mimeCandidates = new String[] {
            mimeType != null && !mimeType.trim().isEmpty() ? mimeType : "*/*",
            "*/*",
        };

        for (String candidateMime : mimeCandidates) {
            Intent intent = buildViewIntent(docName, openUri, candidateMime);
            List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            );
            grantReadAccessToHandlers(openUri, handlers);

            Intent launchIntent = intent;
            if (handlers != null && handlers.size() > 1) {
                Intent chooser = Intent.createChooser(intent, "Open with");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                chooser.setClipData(
                    ClipData.newUri(getContentResolver(), docName, openUri)
                );
                launchIntent = chooser;
            }

            try {
                startActivity(launchIntent);
                return true;
            } catch (Exception ignored) {
                // Try the next MIME candidate before giving up.
            }
        }
        return false;
    }

    private Intent buildViewIntent(String docName, Uri openUri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(openUri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setClipData(ClipData.newUri(getContentResolver(), docName, openUri));
        return intent;
    }

    private void grantReadAccessToHandlers(Uri openUri, List<ResolveInfo> handlers) {
        if (openUri == null || handlers == null) {
            return;
        }
        for (ResolveInfo handler : new ArrayList<>(handlers)) {
            if (
                handler == null ||
                handler.activityInfo == null ||
                handler.activityInfo.packageName == null
            ) {
                continue;
            }
            try {
                grantUriPermission(
                    handler.activityInfo.packageName,
                    openUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
                // Continue granting other handlers even if one grant fails.
            }
        }
    }

    private boolean isPdfDocument(String mimeType, String docName) {
        return "application/pdf".equalsIgnoreCase(mimeType) ||
        "pdf".equalsIgnoreCase(FileUtils.getExtension(docName));
    }

    private void openPdfFallback(String docName, Uri openUri) {
        Intent fallbackIntent = new Intent(this, PdfViewerActivity.class);
        fallbackIntent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, openUri.toString());
        fallbackIntent.putExtra(PdfViewerActivity.EXTRA_TITLE, docName);
        startActivity(fallbackIntent);
    }

    private void preselectCorrection(String category) {
        int chipId;
        if (category == null) chipId = R.id.chipCorGeneral;
        else switch (category) {
            case "Technical":
                chipId = R.id.chipCorTechnical;
                break;
            case "Research":
                chipId = R.id.chipCorResearch;
                break;
            case "Legal":
                chipId = R.id.chipCorLegal;
                break;
            case "Medical":
                chipId = R.id.chipCorMedical;
                break;
            default:
                chipId = R.id.chipCorGeneral;
                break;
        }
        Chip chip = chipGroupCorrection.findViewById(chipId);
        if (chip != null) chip.setChecked(true);
    }

    private String chipIdToCategory(int id) {
        if (id == R.id.chipCorTechnical) return "Technical";
        if (id == R.id.chipCorResearch) return "Research";
        if (id == R.id.chipCorLegal) return "Legal";
        if (id == R.id.chipCorMedical) return "Medical";
        if (id == R.id.chipCorGeneral) return "General";
        return null;
    }

    private String getSummaryActionText() {
        if (
            currentDoc != null &&
            currentDoc.summary != null &&
            !currentDoc.summary.isEmpty()
        ) {
            return "Refresh summary";
        }
        return "Create summary";
    }

    private void renderMarkdownSummary(String text) {
        if (text == null) {
            txtSummary.setText("");
            return;
        }
        txtSummary.setMaxLines(summaryExpanded ? Integer.MAX_VALUE : 7);
        txtSummary.setEllipsize(summaryExpanded ? null : android.text.TextUtils.TruncateAt.END);
        txtSummary.setGravity(Gravity.START);
        txtSummary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            txtSummary.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
        try {
            markwon.setMarkdown(txtSummary, text);
        } catch (Exception ignored) {
            txtSummary.setText(text);
        }
    }

    private void toggleSummary() {
        if (currentDoc == null || currentDoc.summary == null || currentDoc.summary.isEmpty()) {
            return;
        }
        summaryExpanded = !summaryExpanded;
        renderMarkdownSummary(currentDoc.summary);
        updateSummaryToggle(currentDoc.summary);
    }

    private void updateSummaryToggle(String summary) {
        boolean hasSummary = summary != null && !summary.trim().isEmpty();
        boolean shouldShowToggle = hasSummary && summary.trim().length() > 220;
        btnToggleSummary.setVisibility(shouldShowToggle ? View.VISIBLE : View.GONE);
        if (shouldShowToggle) {
            btnToggleSummary.setText(summaryExpanded ? "Show less" : "Show more");
        }
    }

    private void styleDisplayChip(Chip chip) {
        if (chip == null) {
            return;
        }
        int background = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorSurfaceVariant
        );
        int foreground = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        );
        int stroke = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorOutlineVariant
        );
        applyDisplayChipStyle(chip, background, foreground, stroke);
    }

    private void stylePlaceholderChip(Chip chip) {
        if (chip == null) {
            return;
        }
        int background = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        );
        int foreground = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        );
        int stroke = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorOutlineVariant
        );
        applyDisplayChipStyle(chip, background, foreground, stroke);
        chip.setAlpha(0.8f);
    }

    private void styleCategoryChip(Chip chip) {
        if (chip == null) {
            return;
        }
        int background = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorPrimaryContainer
        );
        int foreground = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorOnPrimaryContainer
        );
        int stroke = MaterialColors.getColor(
            chip,
            com.google.android.material.R.attr.colorPrimary
        );
        applyDisplayChipStyle(chip, background, foreground, stroke);
    }

    private void applyDisplayChipStyle(
        Chip chip,
        int background,
        int foreground,
        int stroke
    ) {
        chip.setChipStrokeWidth(getResources().getDisplayMetrics().density);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(getResources().getDisplayMetrics().density * 34f);
        chip.setChipCornerRadius(getResources().getDisplayMetrics().density * 17f);
        chip.setChipBackgroundColor(ColorStateList.valueOf(background));
        chip.setTextColor(foreground);
        chip.setChipStrokeColor(ColorStateList.valueOf(stroke));
    }

    private void renderEntityChips() {
        chipGroupEntities.removeAllViews();
        int limit = entitiesExpanded
            ? currentEntities.size()
            : Math.min(8, currentEntities.size());
        for (int i = 0; i < limit; i++) {
            String entity = currentEntities.get(i);
            Chip chip = new Chip(this);
            chip.setText(entity);
            chip.setClickable(false);
            chip.setFocusable(false);
            chip.setTypeface(
                ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
            );
            styleDisplayChip(chip);
            chipGroupEntities.addView(chip);
        }
    }

    private void toggleEntities() {
        entitiesExpanded = !entitiesExpanded;
        renderEntityChips();
        updateEntitiesToggle();
    }

    private void updateEntitiesToggle() {
        boolean shouldShow = currentEntities.size() > 8;
        btnToggleEntities.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        if (shouldShow) {
            btnToggleEntities.setText(
                entitiesExpanded ? "Show fewer entities" : "Show all entities"
            );
        }
    }
}
