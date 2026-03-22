package com.semantic.ekko.ui.qa;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.semantic.ekko.R;

public class QAActivity extends AppCompatActivity {

    private QAViewModel viewModel;
    private QAAdapter adapter;

    private RecyclerView recyclerMessages;
    private EditText editQuestion;
    private ImageButton btnSend;
    private ImageButton btnStop;
    private LinearProgressIndicator progressLoading;

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

    private void bindViews() {
        recyclerMessages = findViewById(R.id.recyclerMessages);
        editQuestion = findViewById(R.id.editQuestion);
        btnSend = findViewById(R.id.btnSend);
        btnStop = findViewById(R.id.btnStop);
        progressLoading = findViewById(R.id.progressLoading);
    }

    private void setupRecycler() {
        // Pass context so QAAdapter can initialise Markwon
        adapter = new QAAdapter(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(layoutManager);
        recyclerMessages.setAdapter(adapter);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(QAViewModel.class);

        viewModel
            .getUiEvent()
            .observe(this, event -> {
                if (event == null) return;

                switch (event.type) {
                    case QAViewModel.UiEvent.ADD_MESSAGE:
                        if (
                            event.message.type == QAMessage.TYPE_USER ||
                            event.message.type == QAMessage.TYPE_ANSWER
                        ) {
                            streamingBuffer.setLength(0);
                        }
                        adapter.addMessage(event.message);
                        scrollToBottom();
                        break;
                    case QAViewModel.UiEvent.UPDATE_LAST:
                        if (event.token != null) {
                            streamingBuffer.append(event.token);
                            adapter.appendStreamingToken(
                                streamingBuffer.toString()
                            );
                        } else if (event.source != null) {
                            // Streaming complete - apply markdown and set source
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
