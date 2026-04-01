package com.semantic.ekko.ui.detail;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import io.noties.markwon.Markwon;
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
    private TextView txtSummaryHint;
    private Markwon markwon;
    private boolean mlReady = false;
    private boolean backendReady = false;
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
        txtSummaryHint = findViewById(R.id.txtSummaryHint);

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
                    renderMarkdownSummary(summary);
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
        app.refreshBackendHealthAsync();
        updateActionReadiness();
    }

    private void updateActionReadiness() {
        boolean ready = mlReady && backendReady;
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
        }
    }

    private void bindDocument(DocumentEntity doc) {
        txtDocName.setText(doc.name);
        txtFileType.setText(
            doc.fileType != null ? doc.fileType.toUpperCase() : "FILE"
        );
        chipCategory.setText(doc.category != null ? doc.category : "General");

        String wordLabel =
            doc.wordCount >= 1000
                ? String.format("%.1fk", doc.wordCount / 1000f)
                : String.valueOf(doc.wordCount);
        txtWordCount.setText(wordLabel);
        txtReadTime.setText(String.valueOf(Math.max(1, doc.wordCount / 200)));

        if (doc.summary != null && !doc.summary.isEmpty()) {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummaryHint.setText("Saved summary from the indexed document");
            renderMarkdownSummary(doc.summary);
            btnEnhanceSummary.setText("Refresh summary");
        } else {
            txtSummary.setVisibility(View.VISIBLE);
            txtSummaryHint.setText(
                "Create a quick overview from the indexed text already stored on-device"
            );
            renderMarkdownSummary(
                "No summary yet. Use the action above to create one."
            );
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
                chipGroupKeywords.addView(chip);
            }
        }

        chipGroupEntities.removeAllViews();
        List<String> entities = EntityExtractorHelper.entitiesFromString(
            doc.entities
        );
        txtEntityCount.setText(String.valueOf(entities.size()));

        if (!entities.isEmpty()) {
            labelEntities.setVisibility(View.VISIBLE);
            chipGroupEntities.setVisibility(View.VISIBLE);
            for (String entity : entities) {
                Chip chip = new Chip(this);
                chip.setText(entity);
                chip.setClickable(false);
                chip.setFocusable(false);
                chip.setTypeface(
                    ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
                );
                chipGroupEntities.addView(chip);
            }
        } else {
            labelEntities.setVisibility(View.GONE);
            chipGroupEntities.setVisibility(View.GONE);
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
        try {
            Uri sourceUri = resolveSourceUri(doc, folder);
            String mimeType = FileUtils.resolveMimeType(this, sourceUri, doc.name);
            Uri openUri = FileUtils.copyToViewerCache(this, sourceUri, doc.name);

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
            "Could not open file. Re-select the source folder if access was revoked.",
            Snackbar.LENGTH_SHORT
        ).show();
    }

    private boolean tryOpenInNativeApp(
        String docName,
        Uri openUri,
        String mimeType
    ) {
        Intent intent = buildViewIntent(docName, openUri, mimeType);
        Intent chooser = Intent.createChooser(intent, "Open with");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(ClipData.newUri(getContentResolver(), docName, openUri));

        try {
            startActivity(chooser);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Intent buildViewIntent(String docName, Uri openUri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(openUri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), docName, openUri));
        return intent;
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
        try {
            markwon.setMarkdown(txtSummary, text);
        } catch (Exception ignored) {
            txtSummary.setText(text);
        }
    }
}
