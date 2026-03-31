package com.semantic.ekko.ui.onboarding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.semantic.ekko.R;
import java.util.List;

public class OnboardingPagerAdapter
    extends RecyclerView.Adapter<OnboardingPagerAdapter.OnboardingViewHolder>
{

    private final List<OnboardingPage> pages;

    public OnboardingPagerAdapter(List<OnboardingPage> pages) {
        this.pages = pages;
    }

    @NonNull
    @Override
    public OnboardingViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent,
        int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
            R.layout.item_onboarding_page,
            parent,
            false
        );
        return new OnboardingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
        @NonNull OnboardingViewHolder holder,
        int position
    ) {
        holder.bind(pages.get(position));
    }

    @Override
    public int getItemCount() {
        return pages.size();
    }

    static class OnboardingViewHolder extends RecyclerView.ViewHolder {

        private final TextView stepLabel;
        private final TextView title;
        private final TextView body;
        private final TextView highlight;
        private final TextView tip;
        private final ImageView icon;

        OnboardingViewHolder(@NonNull View itemView) {
            super(itemView);
            stepLabel = itemView.findViewById(R.id.txtOnboardingStep);
            title = itemView.findViewById(R.id.txtOnboardingTitle);
            body = itemView.findViewById(R.id.txtOnboardingBody);
            highlight = itemView.findViewById(R.id.txtOnboardingHighlight);
            tip = itemView.findViewById(R.id.txtOnboardingTip);
            icon = itemView.findViewById(R.id.imgOnboardingIcon);
        }

        void bind(OnboardingPage page) {
            stepLabel.setText(page.stepLabelRes);
            title.setText(page.titleRes);
            body.setText(page.bodyRes);
            highlight.setText(page.highlightRes);
            tip.setText(page.tipRes);
            icon.setImageResource(page.iconRes);
        }
    }

    public static class OnboardingPage {

        final int stepLabelRes;
        final int titleRes;
        final int bodyRes;
        final int highlightRes;
        final int tipRes;
        final int iconRes;

        public OnboardingPage(
            int stepLabelRes,
            int titleRes,
            int bodyRes,
            int highlightRes,
            int tipRes,
            int iconRes
        ) {
            this.stepLabelRes = stepLabelRes;
            this.titleRes = titleRes;
            this.bodyRes = bodyRes;
            this.highlightRes = highlightRes;
            this.tipRes = tipRes;
            this.iconRes = iconRes;
        }
    }
}
