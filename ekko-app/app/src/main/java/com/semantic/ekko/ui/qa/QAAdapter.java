package com.semantic.ekko.ui.qa;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import io.noties.markwon.Markwon;
import java.util.ArrayList;
import java.util.List;

public class QAAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<QAMessage> messages = new ArrayList<>();
    private final Markwon markwon;

    @Nullable
    private AnswerViewHolder activeHolder = null;

    public QAAdapter(Context context) {
        this.markwon = Markwon.create(context);
    }

    // =========================
    // VIEW HOLDERS
    // =========================

    static class UserViewHolder extends RecyclerView.ViewHolder {

        final TextView txtQuestion;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            txtQuestion = itemView.findViewById(R.id.txtQuestion);
        }
    }

    static class AnswerViewHolder extends RecyclerView.ViewHolder {

        final TextView txtAnswer;
        final TextView txtSource;

        AnswerViewHolder(@NonNull View itemView) {
            super(itemView);
            txtAnswer = itemView.findViewById(R.id.txtAnswer);
            txtSource = itemView.findViewById(R.id.txtSource);
        }
    }

    static class ErrorViewHolder extends RecyclerView.ViewHolder {

        final TextView txtError;

        ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            txtError = itemView.findViewById(R.id.txtError);
        }
    }

    // =========================
    // ADAPTER
    // =========================

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent,
        int viewType
    ) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case QAMessage.TYPE_USER:
                return new UserViewHolder(
                    inflater.inflate(R.layout.item_qa_user, parent, false)
                );
            case QAMessage.TYPE_ANSWER:
                return new AnswerViewHolder(
                    inflater.inflate(R.layout.item_qa_answer, parent, false)
                );
            default:
                return new ErrorViewHolder(
                    inflater.inflate(R.layout.item_qa_error, parent, false)
                );
        }
    }

    @Override
    public void onBindViewHolder(
        @NonNull RecyclerView.ViewHolder holder,
        int position
    ) {
        QAMessage message = messages.get(position);

        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).txtQuestion.setText(message.text);
        } else if (holder instanceof AnswerViewHolder) {
            AnswerViewHolder h = (AnswerViewHolder) holder;
            String renderedText = sanitizeAnswerMarkdown(message.text);

            // Always render markdown on bind
            markwon.setMarkdown(h.txtAnswer, renderedText);

            h.txtSource.setVisibility(View.GONE);
            h.txtSource.setText("");
            if (
                message.sourceDocumentName != null &&
                !message.sourceDocumentName.isEmpty()
            ) {
                h.txtSource.setVisibility(View.VISIBLE);
                h.txtSource.setText("From: " + message.sourceDocumentName);
            }

            boolean isLast = position == messages.size() - 1;
            if (isLast && message.sourceDocumentName == null) {
                activeHolder = h;
            } else {
                if (activeHolder == h) activeHolder = null;
            }
        } else if (holder instanceof ErrorViewHolder) {
            ((ErrorViewHolder) holder).txtError.setText(message.text);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof AnswerViewHolder && activeHolder == holder) {
            activeHolder = null;
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // =========================
    // UPDATE METHODS
    // =========================

    public void addMessage(QAMessage message) {
        if (message.type == QAMessage.TYPE_USER) activeHolder = null;
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /**
     * Called on every flush (every 80ms) during streaming.
     * Applies Markwon to the accumulated text so markdown renders
     * progressively as the answer streams in, not just at the end.
     * Direct view update - no notify call to avoid layout thrash.
     */
    public void appendStreamingToken(String fullText) {
        if (messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        QAMessage existing = messages.get(lastIndex);
        messages.set(
            lastIndex,
            new QAMessage(existing.type, fullText, existing.sourceDocumentName)
        );
        if (activeHolder != null) {
            markwon.setMarkdown(
                activeHolder.txtAnswer,
                sanitizeAnswerMarkdown(fullText)
            );
        }
    }

    /**
     * Called when streaming is complete. Final markdown render and source label.
     */
    public void finalizeStreamingMessage(
        String fullText,
        String sourceDocumentName
    ) {
        if (messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        messages.set(
            lastIndex,
            new QAMessage(QAMessage.TYPE_ANSWER, fullText, sourceDocumentName)
        );

        if (activeHolder != null) {
            markwon.setMarkdown(
                activeHolder.txtAnswer,
                sanitizeAnswerMarkdown(fullText)
            );
            if (sourceDocumentName != null && !sourceDocumentName.isEmpty()) {
                activeHolder.txtSource.setText("From: " + sourceDocumentName);
                activeHolder.txtSource.setVisibility(View.VISIBLE);
            } else {
                activeHolder.txtSource.setVisibility(View.GONE);
            }
            activeHolder = null;
        } else {
            notifyItemChanged(lastIndex);
        }
    }

    public void replaceLastMessage(QAMessage message) {
        if (messages.isEmpty()) return;
        activeHolder = null;
        int lastIndex = messages.size() - 1;
        messages.set(lastIndex, message);
        notifyItemChanged(lastIndex);
    }

    public void clearMessages() {
        activeHolder = null;
        int size = messages.size();
        messages.clear();
        notifyItemRangeRemoved(0, size);
    }

    private String sanitizeAnswerMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
            .replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1")
            .replaceAll("(?<!_)_([^_\\n]+)_(?!_)", "$1");
    }
}
