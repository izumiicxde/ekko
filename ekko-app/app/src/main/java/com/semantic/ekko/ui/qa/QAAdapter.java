package com.semantic.ekko.ui.qa;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import java.util.ArrayList;
import java.util.List;

public class QAAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<QAMessage> messages = new ArrayList<>();

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

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case QAMessage.TYPE_USER:
                return new UserViewHolder(inflater.inflate(R.layout.item_qa_user, parent, false));
            case QAMessage.TYPE_ANSWER:
                return new AnswerViewHolder(inflater.inflate(R.layout.item_qa_answer, parent, false));
            default:
                return new ErrorViewHolder(inflater.inflate(R.layout.item_qa_error, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        QAMessage message = messages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).txtQuestion.setText(message.text);
        } else if (holder instanceof AnswerViewHolder) {
            AnswerViewHolder h = (AnswerViewHolder) holder;
            h.txtAnswer.setText(message.text);
            if (message.sourceDocumentName != null && !message.sourceDocumentName.isEmpty()) {
                h.txtSource.setVisibility(View.VISIBLE);
                h.txtSource.setText("From: " + message.sourceDocumentName);
            } else {
                h.txtSource.setVisibility(View.GONE);
            }
        } else if (holder instanceof ErrorViewHolder) {
            ((ErrorViewHolder) holder).txtError.setText(message.text);
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    public void setMessages(List<QAMessage> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        notifyDataSetChanged();
    }
}
