package com.semantic.ekko.ui.pdf;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.util.FileUtils;
import java.io.IOException;

public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "pdf_uri";
    public static final String EXTRA_TITLE = "title";

    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer pdfRenderer;
    private PdfRenderer.Page currentPage;
    private Bitmap currentBitmap;
    private int currentPageIndex = 0;

    private View root;
    private ImageView imagePdfPage;
    private TextView txtTitle;
    private TextView txtPageIndicator;
    private MaterialButton btnPrevious;
    private MaterialButton btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        bindViews();
        applyInsets();

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        txtTitle.setText(
            title == null || title.trim().isEmpty()
                ? getString(R.string.pdf_viewer_title)
                : title
        );

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnPrevious.setOnClickListener(v -> showPage(currentPageIndex - 1));
        btnNext.setOnClickListener(v -> showPage(currentPageIndex + 1));

        String pdfUri = getIntent().getStringExtra(EXTRA_PDF_URI);
        if (pdfUri == null || pdfUri.trim().isEmpty()) {
            showOpenErrorAndFinish();
            return;
        }

        try {
            fileDescriptor = FileUtils.openFileDescriptor(this, Uri.parse(pdfUri));
            if (fileDescriptor == null) {
                showOpenErrorAndFinish();
                return;
            }
            pdfRenderer = new PdfRenderer(fileDescriptor);
            imagePdfPage.post(() -> showPage(0));
        } catch (Exception e) {
            showOpenErrorAndFinish();
        }
    }

    private void bindViews() {
        root = findViewById(R.id.pdfViewerRoot);
        imagePdfPage = findViewById(R.id.imagePdfPage);
        txtTitle = findViewById(R.id.txtPdfTitle);
        txtPageIndicator = findViewById(R.id.txtPageIndicator);
        btnPrevious = findViewById(R.id.btnPreviousPage);
        btnNext = findViewById(R.id.btnNextPage);
    }

    private void applyInsets() {
        int baseTop = root.getPaddingTop();
        int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
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

    private void showPage(int pageIndex) {
        if (pdfRenderer == null) return;
        if (pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) return;
        closeCurrentPage();

        currentPageIndex = pageIndex;
        currentPage = pdfRenderer.openPage(pageIndex);

        int availableWidth = Math.max(
            720,
            imagePdfPage.getWidth() > 0
                ? imagePdfPage.getWidth()
                : getResources().getDisplayMetrics().widthPixels - dpToPx(32)
        );
        float scale = (float) availableWidth / currentPage.getWidth();
        int bitmapWidth = Math.max(1, Math.round(currentPage.getWidth() * scale));
        int bitmapHeight = Math.max(
            1,
            Math.round(currentPage.getHeight() * scale)
        );

        currentBitmap = Bitmap.createBitmap(
            bitmapWidth,
            bitmapHeight,
            Bitmap.Config.ARGB_8888
        );
        currentBitmap.eraseColor(0xFFFFFFFF);
        currentPage.render(
            currentBitmap,
            null,
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        );
        imagePdfPage.setImageBitmap(currentBitmap);

        int pageCount = pdfRenderer.getPageCount();
        txtPageIndicator.setText(
            getString(R.string.pdf_viewer_page_indicator, pageIndex + 1, pageCount)
        );
        btnPrevious.setEnabled(pageIndex > 0);
        btnNext.setEnabled(pageIndex < pageCount - 1);
    }

    private int dpToPx(int dp) {
        return Math.round(
            dp * getResources().getDisplayMetrics().density
        );
    }

    private void closeCurrentPage() {
        if (currentPage != null) {
            currentPage.close();
            currentPage = null;
        }
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    private void showOpenErrorAndFinish() {
        Snackbar.make(
            root,
            R.string.pdf_viewer_open_error,
            Snackbar.LENGTH_LONG
        ).show();
        root.postDelayed(this::finish, 1200L);
    }

    @Override
    protected void onDestroy() {
        closeCurrentPage();
        if (pdfRenderer != null) {
            pdfRenderer.close();
            pdfRenderer = null;
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException ignored) {
                // no-op
            }
            fileDescriptor = null;
        }
        super.onDestroy();
    }
}
