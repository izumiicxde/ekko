package com.semantic.ekko.ui.qa;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import java.util.ArrayList;
import java.util.List;

public class QAAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<QAMessage> messages = new ArrayList<>();

    // Hold reference to the entire active AnswerViewHolder so we can
    // update both txtAnswer and txtSource directly without any notify call
    @Nullable
    private AnswerViewHolder activeHolder = null;

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
            h.txtAnswer.setText(message.text);

            // Always reset source label first to prevent stale content
            h.txtSource.setVisibility(View.GONE);
            h.txtSource.setText("");

            if (
                message.sourceDocumentName != null &&
                !message.sourceDocumentName.isEmpty()
            ) {
                h.txtSource.setVisibility(View.VISIBLE);
                h.txtSource.setText("From: " + message.sourceDocumentName);
            }

            // Register as active streaming holder if this is the last item
            // and streaming is not yet complete (no source set)
            boolean isLast = position == messages.size() - 1;
            if (isLast && message.sourceDocumentName == null) {
                activeHolder = h;
            } else {
                // Not the streaming item, clear active holder if it was this view
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
        // Clear active holder when adding a new message so the previous
        // answer's holder is no longer considered active
        if (message.type == QAMessage.TYPE_USER) {
            activeHolder = null;
        }
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /**
     * Updates answer text directly on the active ViewHolder without any
     * RecyclerView rebind. Also updates backing data for recycle safety.
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
            activeHolder.txtAnswer.setText(fullText);
        }
    }

    /**
     * Called when streaming is complete. Updates the source label directly
     * on the active ViewHolder and updates backing data. No notify call needed
     * since we are updating views directly - this eliminates the stale source
     * label bug caused by notifyItemChanged not triggering a full rebind.
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
            activeHolder.txtAnswer.setText(fullText);
            if (sourceDocumentName != null && !sourceDocumentName.isEmpty()) {
                activeHolder.txtSource.setText("From: " + sourceDocumentName);
                activeHolder.txtSource.setVisibility(View.VISIBLE);
            } else {
                activeHolder.txtSource.setVisibility(View.GONE);
            }
            activeHolder = null;
        } else {
            // Fallback: ViewHolder was recycled, use notifyItemChanged
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
}
