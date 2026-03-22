package com.semantic.ekko.ui.qa;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.semantic.ekko.EkkoApp;
import com.semantic.ekko.data.repository.RagRepository;
import com.semantic.ekko.ml.EmbeddingEngine;
import java.util.ArrayList;
import java.util.List;

public class QAViewModel extends AndroidViewModel {

    private final MutableLiveData<List<QAMessage>> messages  = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean>         isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String>          error     = new MutableLiveData<>();

    private RagRepository ragRepository;

    public QAViewModel(@NonNull Application application) {
        super(application);
        EkkoApp app = EkkoApp.getInstance();
        if (app.isMlReady()) {
            EmbeddingEngine engine = app.getEmbeddingEngine();
            ragRepository = new RagRepository(application, engine);
        }
    }

    public void ask(String question) {
        if (question == null || question.trim().isEmpty()) return;
        if (ragRepository == null) {
            error.postValue("ML not ready. Please wait and try again.");
            return;
        }
        addMessage(new QAMessage(QAMessage.TYPE_USER, question.trim(), null));
        isLoading.postValue(true);
        error.postValue(null);

        ragRepository.query(question.trim(), new RagRepository.RagCallback() {
            @Override
            public void onAnswer(String answer, String sourceDocumentName) {
                isLoading.postValue(false);
                addMessage(new QAMessage(QAMessage.TYPE_ANSWER, answer, sourceDocumentName));
            }
            @Override
            public void onError(String message) {
                isLoading.postValue(false);
                addMessage(new QAMessage(QAMessage.TYPE_ERROR, message, null));
            }
        });
    }

    private void addMessage(QAMessage message) {
        List<QAMessage> current = messages.getValue();
        List<QAMessage> updated = current != null ? new ArrayList<>(current) : new ArrayList<>();
        updated.add(message);
        messages.postValue(updated);
    }

    public void clearMessages() { messages.postValue(new ArrayList<>()); }

    public LiveData<List<QAMessage>> getMessages() { return messages; }
    public LiveData<Boolean>         getIsLoading() { return isLoading; }
    public LiveData<String>          getError()     { return error; }
}
