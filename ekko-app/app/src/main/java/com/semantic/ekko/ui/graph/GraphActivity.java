package com.semantic.ekko.ui.graph;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.detail.DetailActivity;

public class GraphActivity extends AppCompatActivity {

    private GraphViewModel viewModel;
    private ClusterGraphView graphView;
    private ProgressBar progressBar;
    private View emptyState;
    private TextView txtTitle;
    private TextView txtSubtitle;
    private TextView txtEmptyMessage;
    private MaterialButton btnScope;
    private MaterialButton btnModeCluster;
    private MaterialButton btnModeFolder;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);

        bindViews();
        applyInsets();
        viewModel = new ViewModelProvider(this).get(GraphViewModel.class);
        observeState();
        setupListeners();
    }

    private void bindViews() {
        graphView = findViewById(R.id.graphView);
        progressBar = findViewById(R.id.progressGraph);
        emptyState = findViewById(R.id.layoutGraphEmpty);
        txtTitle = findViewById(R.id.txtGraphTitle);
        txtSubtitle = findViewById(R.id.txtGraphSubtitle);
        txtEmptyMessage = findViewById(R.id.txtGraphEmptyMessage);
        btnScope = findViewById(R.id.btnGraphScope);
        btnModeCluster = findViewById(R.id.btnGraphModeCluster);
        btnModeFolder = findViewById(R.id.btnGraphModeFolder);
    }

    private void observeState() {
        viewModel
            .getState()
            .observe(this, state -> {
                if (state == null) {
                    return;
                }
                txtTitle.setText(state.title);
                txtSubtitle.setText(
                    state.subtitle == null ? "" : state.subtitle
                );
                txtSubtitle.setVisibility(
                    state.subtitle == null || state.subtitle.isEmpty()
                        ? View.GONE
                        : View.VISIBLE
                );
                updateModeButtons(state.mode);
                progressBar.setVisibility(state.loading ? View.VISIBLE : View.GONE);
                boolean isEmpty = !state.loading && state.scene == null;
                emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                graphView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                txtEmptyMessage.setText(
                    state.emptyMessage == null
                        ? "No indexed data yet."
                        : state.emptyMessage
                );

                if (state.scene != null) {
                    graphView.setScene(state.scene);
                    graphView.setAlpha(0.82f);
                    graphView.setTranslationY(20f);
                    graphView.animate().alpha(1f).translationY(0f).setDuration(320L).start();
                } else {
                    graphView.setScene(null);
                }

                if (state.actionLabel == null || state.actionLabel.isEmpty()) {
                    btnScope.setVisibility(View.GONE);
                } else {
                    btnScope.setVisibility(View.VISIBLE);
                    btnScope.setText(state.actionLabel);
                }
            });
    }

    private void applyInsets() {
        View root = findViewById(R.id.graphRoot);
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

    private void setupListeners() {
        findViewById(R.id.btnGraphBack).setOnClickListener(v -> handleBackAction());
        btnScope.setOnClickListener(v -> viewModel.showOverview());
        btnModeCluster.setOnClickListener(v -> viewModel.showClusterMode());
        btnModeFolder.setOnClickListener(v -> viewModel.showFolderMode());
        graphView.setNodeTapListener(node -> {
            if (node == null) {
                return;
            }
            if (node.type == GraphNode.TYPE_CLUSTER) {
                String category = node.id.replace("__category__:", "");
                viewModel.openCategory(category);
                return;
            }
            if (node.type == GraphNode.TYPE_FOLDER) {
                String folderPath = node.id.replace("__folder__:", "");
                viewModel.openFolder(folderPath);
                return;
            }
            if (node.documentId > 0L) {
                Intent intent = new Intent(this, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_DOCUMENT_ID, node.documentId);
                startActivity(intent);
            }
        });
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleBackAction();
                }
            }
        );
    }

    private void updateModeButtons(int mode) {
        boolean folderMode = mode == GraphViewModel.MODE_FOLDER;
        btnModeCluster.setChecked(!folderMode);
        btnModeFolder.setChecked(folderMode);
    }

    private void handleBackAction() {
        if (!viewModel.isShowingOverview()) {
            viewModel.showOverview();
            return;
        }
        finish();
    }
}
