package com.semantic.ekko.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.semantic.ekko.R;
import com.semantic.ekko.ui.detail.DetailActivity;

public class SearchFragment extends Fragment {

    private static final long DEBOUNCE_DELAY_MS = 400;

    private SearchViewModel viewModel;
    private SearchResultAdapter adapter;

    private EditText editSearch;
    private ImageView btnSearch;
    private LinearProgressIndicator progressSearch;
    private RecyclerView recyclerResults;
    private LinearLayout layoutEmptyState;
    private TextView txtResultCount;
    private TextView txtEmptyTitle;
    private TextView txtEmptySubtitle;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        setupRecycler();
        setupViewModel();
        setupSearchInput();
    }

    // =========================
    // BIND
    // =========================

    private void bindViews(View view) {
        editSearch = view.findViewById(R.id.editSearch);
        btnSearch = view.findViewById(R.id.btnSearch);
        progressSearch = view.findViewById(R.id.progressSearch);
        recyclerResults = view.findViewById(R.id.recyclerResults);
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
        txtResultCount = view.findViewById(R.id.txtResultCount);
        txtEmptyTitle = view.findViewById(R.id.txtEmptyTitle);
        txtEmptySubtitle = view.findViewById(R.id.txtEmptySubtitle);
    }

    // =========================
    // RECYCLER
    // =========================

    private void setupRecycler() {
        adapter = new SearchResultAdapter(result -> {
            Intent intent = new Intent(getActivity(), DetailActivity.class);
            intent.putExtra(
                DetailActivity.EXTRA_DOCUMENT_ID,
                result.getDocument().id
            );
            startActivity(intent);
        });
        recyclerResults.setLayoutManager(new LinearLayoutManager(getContext()));
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
            .observe(getViewLifecycleOwner(), results -> {
                if (results == null) return;
                adapter.submitList(results);

                if (results.isEmpty()) {
                    txtResultCount.setVisibility(View.GONE);
                    recyclerResults.setVisibility(View.GONE);
                    layoutEmptyState.setVisibility(View.VISIBLE);
                    String query = editSearch.getText().toString().trim();
                    if (query.isEmpty()) {
                        txtEmptyTitle.setText("Search your documents");
                        txtEmptySubtitle.setText(
                            "Type a query above to find relevant documents using semantic search."
                        );
                    } else {
                        txtEmptyTitle.setText("No results found");
                        txtEmptySubtitle.setText(
                            "Try a different query or add more documents."
                        );
                    }
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
            .observe(getViewLifecycleOwner(), searching -> {
                progressSearch.setVisibility(
                    searching ? View.VISIBLE : View.GONE
                );
            });

        viewModel
            .getErrorMessage()
            .observe(getViewLifecycleOwner(), msg -> {
                if (msg != null && !msg.isEmpty() && getView() != null) {
                    Snackbar.make(getView(), msg, Snackbar.LENGTH_LONG).show();
                }
            });
    }

    // =========================
    // SEARCH INPUT
    // =========================

    private void setupSearchInput() {
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
                    if (query.isEmpty()) {
                        btnSearch.setImageResource(R.drawable.ic_search);
                    } else {
                        btnSearch.setImageResource(R.drawable.ic_close);
                    }

                    if (query.length() < 2) {
                        if (
                            debounceRunnable != null
                        ) debounceHandler.removeCallbacks(debounceRunnable);
                        clearResults();
                        return;
                    }

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

        btnSearch.setOnClickListener(v -> {
            String query = editSearch.getText().toString().trim();
            if (query.isEmpty()) performSearch();
            else clearInput();
        });
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

    private void clearInput() {
        if (debounceRunnable != null) debounceHandler.removeCallbacks(
            debounceRunnable
        );
        editSearch.setText("");
        btnSearch.setImageResource(R.drawable.ic_search);
        clearResults();
    }

    private void clearResults() {
        adapter.submitList(null);
        txtResultCount.setVisibility(View.GONE);
        recyclerResults.setVisibility(View.GONE);
        layoutEmptyState.setVisibility(View.VISIBLE);
        txtEmptyTitle.setText("Search your documents");
        txtEmptySubtitle.setText(
            "Type a query above to find relevant documents using semantic search."
        );
    }

    private void hideKeyboard() {
        if (getActivity() == null) return;
        InputMethodManager imm =
            (InputMethodManager) getActivity().getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE
            );
        if (imm != null) imm.hideSoftInputFromWindow(
            editSearch.getWindowToken(),
            0
        );
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (debounceRunnable != null) debounceHandler.removeCallbacks(
            debounceRunnable
        );
    }
}
