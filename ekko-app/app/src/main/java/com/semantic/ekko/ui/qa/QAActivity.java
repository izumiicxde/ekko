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
    private QAAdapter   adapter;

    private RecyclerView            recyclerMessages;
    private EditText                editQuestion;
    private ImageButton             btnSend;
    private LinearProgressIndicator progressLoading;

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
        editQuestion     = findViewById(R.id.editQuestion);
        btnSend          = findViewById(R.id.btnSend);
        progressLoading  = findViewById(R.id.progressLoading);
    }

    private void setupRecycler() {
        adapter = new QAAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(layoutManager);
        recyclerMessages.setAdapter(adapter);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(QAViewModel.class);

        viewModel.getMessages().observe(this, messages -> {
            adapter.setMessages(messages);
            if (!messages.isEmpty()) {
                recyclerMessages.smoothScrollToPosition(messages.size() - 1);
            }
        });

        viewModel.getIsLoading().observe(this, loading -> {
            if (loading == null) return;
            progressLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
            btnSend.setEnabled(!loading);
            editQuestion.setEnabled(!loading);
        });
    }

    private void setupInput() {
        btnSend.setOnClickListener(v -> submitQuestion());
        editQuestion.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
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
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editQuestion.getWindowToken(), 0);
    }
}
