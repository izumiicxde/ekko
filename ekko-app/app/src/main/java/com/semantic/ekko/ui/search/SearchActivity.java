package com.semantic.ekko.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.detail.DetailActivity;

public class SearchActivity extends AppCompatActivity {

    private static final long DEBOUNCE_DELAY_MS = 400;

    private SearchViewModel viewModel;
    private SearchResultAdapter adapter;

    private EditText editSearch;
    private LinearProgressIndicator progressSearch;
    private RecyclerView recyclerResults;
    private LinearLayout layoutEmptyState;
    private TextView txtResultCount;
    private TextView txtEmptyTitle;
    private TextView txtEmptySubtitle;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        bindViews();
        setupRecycler();
        setupViewModel();
        setupSearchInput();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        editSearch.requestFocus();
        showKeyboard();
    }

    // =========================
    // BIND
    // =========================

    private void bindViews() {
        editSearch = findViewById(R.id.editSearch);
        progressSearch = findViewById(R.id.progressSearch);
        recyclerResults = findViewById(R.id.recyclerResults);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        txtResultCount = findViewById(R.id.txtResultCount);
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle);
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle);
    }

    // =========================
    // RECYCLER
    // =========================

    private void setupRecycler() {
        adapter = new SearchResultAdapter(result -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(
                DetailActivity.EXTRA_DOCUMENT_ID,
                result.getDocument().id
            );
            startActivity(intent);
        });
        recyclerResults.setLayoutManager(new LinearLayoutManager(this));
        recyclerResults.setAdapter(adapter);
        recyclerResults.setNestedScrollingEnabled(false);
    }

    // =========================
    // VIEWMODEL
    // =========================

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(SearchViewModel.class);

        viewModel
            .getResults()
            .observe(this, results -> {
                if (results == null) return;

                adapter.submitList(results);

                if (results.isEmpty()) {
                    txtResultCount.setVisibility(View.GONE);
                    recyclerResults.setVisibility(View.GONE);
                    layoutEmptyState.setVisibility(View.VISIBLE);
                    txtEmptyTitle.setText("No results found");
                    txtEmptySubtitle.setText(
                        "Try a different query or add more documents."
                    );
                } else {
                    txtResultCount.setVisibility(View.VISIBLE);
                    txtResultCount.setText(
                        results.size() +
                            (results.size() == 1 ? " result" : " results")
                    );
                    recyclerResults.setVisibility(View.VISIBLE);
                    layoutEmptyState.setVisibility(View.GONE);
                }
            });

        viewModel
            .getIsSearching()
            .observe(this, searching -> {
                progressSearch.setVisibility(
                    searching ? View.VISIBLE : View.GONE
                );
            });

        viewModel
            .getErrorMessage()
            .observe(this, msg -> {
                if (msg != null && !msg.isEmpty()) {
                    Snackbar.make(editSearch, msg, Snackbar.LENGTH_LONG).show();
                }
            });
    }

    // =========================
    // SEARCH INPUT
    // =========================

    private void setupSearchInput() {
        // Live search with debounce
        editSearch.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
                ) {}

                @Override
                public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
                ) {}

                @Override
                public void afterTextChanged(Editable s) {
                    String query = s.toString().trim();
                    if (query.length() < 2) return;

                    if (
                        debounceRunnable != null
                    ) debounceHandler.removeCallbacks(debounceRunnable);
                    debounceRunnable = () -> viewModel.search(query);
                    debounceHandler.postDelayed(
                        debounceRunnable,
                        DEBOUNCE_DELAY_MS
                    );
                }
            }
        );

        // Also search on keyboard search action or enter
        editSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
            ) {
                performSearch();
                return true;
            }
            return false;
        });

        findViewById(R.id.btnSearch).setOnClickListener(v -> performSearch());
    }

    private void performSearch() {
        String query = editSearch.getText().toString().trim();
        if (query.isEmpty()) return;
        if (debounceRunnable != null) debounceHandler.removeCallbacks(
            debounceRunnable
        );
        hideKeyboard();
        viewModel.search(query);
    }

    // =========================
    // KEYBOARD
    // =========================

    private void showKeyboard() {
        editSearch.postDelayed(
            () -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(
                    INPUT_METHOD_SERVICE
                );
                if (imm != null) imm.showSoftInput(
                    editSearch,
                    InputMethodManager.SHOW_IMPLICIT
                );
            },
            200
        );
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(
            INPUT_METHOD_SERVICE
        );
        if (imm != null) imm.hideSoftInputFromWindow(
            editSearch.getWindowToken(),
            0
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (debounceRunnable != null) debounceHandler.removeCallbacks(
            debounceRunnable
        );
    }
}
