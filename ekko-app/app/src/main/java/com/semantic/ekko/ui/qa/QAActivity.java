package com.semantic.ekko.ui.qa;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.semantic.ekko.R;
import java.util.List;

public class QAActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";
    public static final String EXTRA_DOCUMENT_NAME = "document_name";

    private QAViewModel viewModel;
    private QAAdapter adapter;

    private RecyclerView recyclerMessages;
    private EditText editQuestion;
    private ImageButton btnSend;
    private ImageButton btnStop;
    private LinearProgressIndicator progressLoading;
    private LinearLayout layoutEmptyState;
    private ChipGroup chipGroupSuggestions;
    private TextView txtEmptyTitle;
    private TextView txtEmptySubtitle;

    private final StringBuilder streamingBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qa);

        bindViews();
        setupRecycler();
        setupViewModel();
        setupInput();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        streamingBuffer.setLength(0);
        streamingBuffer.append(viewModel.getStreamingBuffer());
        viewModel.restoreIfNeeded();
    }

    private void bindViews() {
        recyclerMessages = findViewById(R.id.recyclerMessages);
        editQuestion = findViewById(R.id.editQuestion);
        btnSend = findViewById(R.id.btnSend);
        btnStop = findViewById(R.id.btnStop);
        progressLoading = findViewById(R.id.progressLoading);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        chipGroupSuggestions = findViewById(R.id.chipGroupSuggestions);
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle);
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle);
    }

    private void setupRecycler() {
        adapter = new QAAdapter(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(layoutManager);
        recyclerMessages.setAdapter(adapter);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(QAViewModel.class);

        // Set document mode if launched from DetailActivity
        long docId = getIntent().getLongExtra(
            EXTRA_DOCUMENT_ID,
            QAViewModel.NO_DOCUMENT
        );
        if (docId != QAViewModel.NO_DOCUMENT) {
            viewModel.setDocumentMode(docId);
        }

        // Update toolbar title and empty state for document mode
        String docName = getIntent().getStringExtra(EXTRA_DOCUMENT_NAME);
        TextView txtTitle = findViewById(R.id.txtQaTitle);
        if (viewModel.isDocumentMode() && docName != null) {
            txtTitle.setText(docName);
            txtEmptyTitle.setText("Ask about this document");
            txtEmptySubtitle.setText(
                "Your questions will be answered using only this file's content."
            );
            setupSuggestions(
                new String[] {
                    "Summarize this document",
                    "What are the key topics?",
                    "What is the main conclusion?",
                }
            );
        } else {
            txtTitle.setText("Ask Ekko");
            txtEmptyTitle.setText("Ask Ekko anything");
            txtEmptySubtitle.setText(
                "Your questions are answered from your indexed documents."
            );
            setupSuggestions(
                new String[] {
                    "What is in my documents?",
                    "Explain the main concepts",
                    "Summarize my files",
                }
            );
        }

        viewModel
            .getUiEvent()
            .observe(this, event -> {
                if (event == null) return;
                switch (event.type) {
                    case QAViewModel.UiEvent.RESTORE_HISTORY:
                        adapter.clearMessages();
                        streamingBuffer.setLength(0);
                        streamingBuffer.append(viewModel.getStreamingBuffer());
                        if (event.history != null) {
                            for (QAMessage msg : event.history)
                                adapter.addMessage(msg);
                            scrollToBottom();
                        }
                        updateEmptyState();
                        break;
                    case QAViewModel.UiEvent.ADD_MESSAGE:
                        if (
                            event.message.type == QAMessage.TYPE_USER ||
                            event.message.type == QAMessage.TYPE_ANSWER
                        ) {
                            streamingBuffer.setLength(0);
                        }
                        adapter.addMessage(event.message);
                        updateEmptyState();
                        scrollToBottom();
                        break;
                    case QAViewModel.UiEvent.UPDATE_LAST:
                        if (event.token != null) {
                            streamingBuffer.append(event.token);
                            adapter.appendStreamingToken(
                                streamingBuffer.toString()
                            );
                        } else if (event.source != null) {
                            adapter.finalizeStreamingMessage(
                                streamingBuffer.toString(),
                                event.source
                            );
                        }
                        break;
                    case QAViewModel.UiEvent.REPLACE_LAST:
                        adapter.replaceLastMessage(event.message);
                        scrollToBottom();
                        break;
                }
            });

        viewModel
            .getIsLoading()
            .observe(this, loading -> {
                if (loading == null) return;
                progressLoading.setVisibility(
                    loading ? View.VISIBLE : View.GONE
                );
                editQuestion.setEnabled(!loading);
                if (loading) {
                    btnSend.setVisibility(View.GONE);
                    btnStop.setVisibility(View.VISIBLE);
                } else {
                    btnStop.setVisibility(View.GONE);
                    btnSend.setVisibility(View.VISIBLE);
                }
            });
    }

    private void setupSuggestions(String[] suggestions) {
        chipGroupSuggestions.removeAllViews();
        for (String suggestion : suggestions) {
            Chip chip = new Chip(this);
            chip.setText(suggestion);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                editQuestion.setText(suggestion);
                editQuestion.setSelection(suggestion.length());
                editQuestion.requestFocus();
            });
            chipGroupSuggestions.addView(chip);
        }
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        layoutEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerMessages.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void scrollToBottom() {
        recyclerMessages.post(() -> {
            int count = adapter.getItemCount();
            if (count > 0) recyclerMessages.scrollToPosition(count - 1);
        });
    }

    private void setupInput() {
        btnSend.setOnClickListener(v -> submitQuestion());
        btnStop.setOnClickListener(v -> viewModel.stop());
        editQuestion.setOnEditorActionListener((v, actionId, event) -> {
            if (
                actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN)
            ) {
                submitQuestion();
                return true;
            }
            return false;
        });
    }

    private void submitQuestion() {
        String question = editQuestion.getText().toString().trim();
        if (question.isEmpty()) return;
        editQuestion.setText("");
        hideKeyboard();
        viewModel.ask(question);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(
            INPUT_METHOD_SERVICE
        );
        if (imm != null) imm.hideSoftInputFromWindow(
            editQuestion.getWindowToken(),
            0
        );
    }
}
