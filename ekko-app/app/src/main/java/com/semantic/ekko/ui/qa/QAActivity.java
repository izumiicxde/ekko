package com.semantic.ekko.ui.qa;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.semantic.ekko.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private TextView txtQaTitle;
    private TextView txtQaSubtitle;
    private ChipGroup chipGroupInputCompletions;

    private final List<String> includedFileNames = new ArrayList<>();
    private int completionReplaceStart = -1;
    private int completionReplaceEnd = -1;
    private int completionMode = 0;
    private static final int MODE_NONE = 0;
    private static final int MODE_AT_FILE = 1;
    private static final int MODE_FILE_COMMAND = 2;
    private static final int MODE_SLASH_COMMAND = 3;

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
        txtQaTitle = findViewById(R.id.txtQaTitle);
        txtQaSubtitle = findViewById(R.id.txtQaSubtitle);
        chipGroupInputCompletions = findViewById(
            R.id.chipGroupInputCompletions
        );
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

        long docId = getIntent().getLongExtra(
            EXTRA_DOCUMENT_ID,
            QAViewModel.NO_DOCUMENT
        );
        if (docId != QAViewModel.NO_DOCUMENT) {
            viewModel.setDocumentMode(docId);
        }

        String docName = getIntent().getStringExtra(EXTRA_DOCUMENT_NAME);
        if (viewModel.isDocumentMode() && docName != null) {
            txtQaTitle.setText(docName);
            txtQaSubtitle.setText("Document-specific answers");
            txtEmptyTitle.setText("Ask about this document");
            txtEmptySubtitle.setText(
                "Answers are generated only from this file's content."
            );
            editQuestion.setHint("Ask anything about this document...");
            setupSuggestions(
                new String[] {
                    "Summarize this document",
                    "What are the key topics?",
                    "What should I review first?",
                }
            );
        } else {
            txtQaTitle.setText("Ekko Bot");
            txtQaSubtitle.setText(
                "Use @file: question or @latest: question for one-file context"
            );
            txtEmptyTitle.setText("Ask Ekko Bot anything");
            txtEmptySubtitle.setText(
                "Answers are grounded in your indexed documents. You can target one file with @filename: ..."
            );
            editQuestion.setHint("Ask anything or use @filename: question");
            setupSuggestions(
                new String[] {
                    "What is in my documents?",
                    "@latest: summarize this file",
                    "/file policy.pdf: list key points",
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

        viewModel.getIncludedFileNames(names -> {
            includedFileNames.clear();
            if (names != null) includedFileNames.addAll(names);
            updateInlineCompletions(editQuestion.getText().toString());
        });
    }

    private void setupSuggestions(String[] suggestions) {
        chipGroupSuggestions.removeAllViews();
        for (String suggestion : suggestions) {
            Chip chip = new Chip(this);
            chip.setText(suggestion);
            chip.setCheckable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipMinHeight(34f);
            chip.setChipCornerRadius(16f);
            chip.setChipStrokeWidth(0f);
            chip.setTextSize(12f);
            chip.setTypeface(
                ResourcesCompat.getFont(this, R.font.bricolage_grotesque)
            );
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
        editQuestion.addTextChangedListener(
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
                ) {
                    updateInlineCompletions(s == null ? "" : s.toString());
                }

                @Override
                public void afterTextChanged(Editable s) {}
            }
        );
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
        hideInlineCompletions();
        hideKeyboard();
        viewModel.ask(question);
    }

    private void updateInlineCompletions(String input) {
        if (viewModel.isDocumentMode()) {
            hideInlineCompletions();
            return;
        }

        int cursor = Math.max(0, editQuestion.getSelectionStart());
        if (cursor > input.length()) cursor = input.length();
        String before = input.substring(0, cursor);

        int tokenStart =
            Math.max(before.lastIndexOf(' '), before.lastIndexOf('\n')) + 1;
        String token = before.substring(tokenStart);
        String tokenLower = token.toLowerCase(Locale.US);

        int segmentStart = before.lastIndexOf('\n') + 1;
        String segment = before.substring(segmentStart);
        String segmentLower = segment.toLowerCase(Locale.US);

        if (segmentLower.startsWith("/file ") && !segment.contains(":")) {
            String query = segment.substring(6).trim().toLowerCase(Locale.US);
            List<String> options = new ArrayList<>();
            for (String file : includedFileNames) {
                if (
                    query.isEmpty() ||
                    file.toLowerCase(Locale.US).contains(query)
                ) {
                    options.add(file);
                }
                if (options.size() >= 6) break;
            }
            showInlineCompletions(
                options,
                MODE_FILE_COMMAND,
                segmentStart,
                cursor
            );
            return;
        }

        if (token.startsWith("@")) {
            String query = token.substring(1).trim().toLowerCase(Locale.US);
            List<String> options = new ArrayList<>();
            if ("latest".contains(query)) options.add("latest");
            for (String file : includedFileNames) {
                if (
                    query.isEmpty() ||
                    file.toLowerCase(Locale.US).contains(query)
                ) {
                    options.add(file);
                }
                if (options.size() >= 6) break;
            }
            showInlineCompletions(options, MODE_AT_FILE, tokenStart, cursor);
            return;
        }

        if (token.startsWith("/")) {
            List<String> options = new ArrayList<>();
            if ("/file".startsWith(tokenLower)) options.add("/file ");
            if ("/latest:".startsWith(tokenLower)) options.add("/latest: ");
            showInlineCompletions(
                options,
                MODE_SLASH_COMMAND,
                tokenStart,
                cursor
            );
            return;
        }

        hideInlineCompletions();
    }

    private void showInlineCompletions(
        List<String> options,
        int mode,
        int replaceStart,
        int replaceEnd
    ) {
        if (options == null || options.isEmpty()) {
            hideInlineCompletions();
            return;
        }
        completionMode = mode;
        completionReplaceStart = replaceStart;
        completionReplaceEnd = replaceEnd;
        chipGroupInputCompletions.removeAllViews();
        for (String value : options) {
            Chip chip = new Chip(this);
            chip.setText(value);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> applyCompletion(value));
            chipGroupInputCompletions.addView(chip);
        }
        chipGroupInputCompletions.setVisibility(View.VISIBLE);
    }

    private void applyCompletion(String value) {
        if (
            completionReplaceStart < 0 ||
            completionReplaceEnd < completionReplaceStart
        ) {
            return;
        }

        String insertion;
        if (completionMode == MODE_AT_FILE) {
            insertion = "@" + value + ": ";
        } else if (completionMode == MODE_FILE_COMMAND) {
            insertion = "/file " + value + ": ";
        } else {
            insertion = value;
        }

        Editable editable = editQuestion.getText();
        editable.replace(
            completionReplaceStart,
            completionReplaceEnd,
            insertion
        );
        editQuestion.setSelection(completionReplaceStart + insertion.length());
        hideInlineCompletions();
    }

    private void hideInlineCompletions() {
        completionMode = MODE_NONE;
        completionReplaceStart = -1;
        completionReplaceEnd = -1;
        chipGroupInputCompletions.setVisibility(View.GONE);
        chipGroupInputCompletions.removeAllViews();
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
