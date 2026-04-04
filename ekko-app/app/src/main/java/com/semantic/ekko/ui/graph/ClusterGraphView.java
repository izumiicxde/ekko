package com.semantic.ekko.ui.graph;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClusterGraphView extends View {

    public interface NodeTapListener {
        void onNodeTapped(GraphNode node);
    }

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<RenderedNode> renderedNodes = new ArrayList<>();
    private final Map<String, RenderedNode> previousNodes = new HashMap<>();

    private GraphScene scene;
    private NodeTapListener nodeTapListener;
    private ValueAnimator sceneAnimator;
    private float sceneTransitionProgress = 1f;

    public ClusterGraphView(Context context) {
        super(context);
        init();
    }

    public ClusterGraphView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ClusterGraphView(
        Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.6f));
        strokePaint.setColor(Color.argb(28, 15, 23, 42));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(Color.argb(20, 32, 84, 215));

        titlePaint.setColor(Color.parseColor("#0F172A"));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextSize(sp(12f));

        detailPaint.setColor(Color.parseColor("#475569"));
        detailPaint.setTextAlign(Paint.Align.CENTER);
        detailPaint.setTextSize(sp(10f));
    }

    public void setScene(@Nullable GraphScene scene) {
        previousNodes.clear();
        for (RenderedNode renderedNode : renderedNodes) {
            previousNodes.put(renderedNode.node.id, renderedNode);
        }
        this.scene = scene;
        relayoutNodes(getWidth(), getHeight());
        startSceneAnimation();
        invalidate();
    }

    public void setNodeTapListener(@Nullable NodeTapListener listener) {
        this.nodeTapListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayoutNodes(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackgroundGrid(canvas);
        if (scene == null) {
            return;
        }
        drawEdges(canvas);
        drawNodes(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || renderedNodes.isEmpty()) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();
        RenderedNode tappedNode = findTappedNode(x, y);
        if (tappedNode != null && nodeTapListener != null) {
            nodeTapListener.onNodeTapped(tappedNode.node);
        }
        return true;
    }

    private void drawBackgroundGrid(Canvas canvas) {
        float inset = dp(12f);
        RectF bounds = new RectF(
            inset,
            inset,
            getWidth() - inset,
            getHeight() - inset
        );
        canvas.drawRoundRect(bounds, dp(26f), dp(26f), gridPaint);

        float step = dp(44f);
        for (float x = inset + step; x < getWidth() - inset; x += step) {
            canvas.drawLine(x, inset, x, getHeight() - inset, gridPaint);
        }
        for (float y = inset + step; y < getHeight() - inset; y += step) {
            canvas.drawLine(inset, y, getWidth() - inset, y, gridPaint);
        }
    }

    private void drawEdges(Canvas canvas) {
        Map<String, RenderedNode> index = new HashMap<>();
        for (RenderedNode node : renderedNodes) {
            index.put(node.node.id, resolveDisplayedNode(node));
        }
        for (GraphEdge edge : scene.edges) {
            RenderedNode from = index.get(edge.fromId);
            RenderedNode to = index.get(edge.toId);
            if (from == null || to == null) {
                continue;
            }
            int color = ColorUtils.setAlphaComponent(
                blendColors(from.node.color, to.node.color),
                (int) (38 + (edge.weight * 110))
            );
            edgePaint.setColor(color);
            edgePaint.setStrokeWidth(dp(1.5f) + (edge.weight * dp(2.1f)));
            canvas.drawLine(from.cx, from.cy, to.cx, to.cy, edgePaint);
        }
    }

    private void drawNodes(Canvas canvas) {
        for (RenderedNode rendered : renderedNodes) {
            RenderedNode displayed = resolveDisplayedNode(rendered);
            int fillColor = ColorUtils.blendARGB(
                rendered.node.color,
                Color.WHITE,
                0.18f
            );
            nodePaint.setColor(fillColor);
            canvas.drawCircle(
                displayed.cx,
                displayed.cy,
                displayed.radius,
                nodePaint
            );
            canvas.drawCircle(
                displayed.cx,
                displayed.cy,
                displayed.radius,
                strokePaint
            );

            String title = fitLine(
                rendered.node.label,
                displayed.radius,
                titlePaint
            );
            String detail = fitLine(
                rendered.node.detail,
                displayed.radius,
                detailPaint
            );
            canvas.drawText(
                title,
                displayed.cx,
                displayed.cy - dp(3f),
                titlePaint
            );
            if (
                displayed.radius >= dp(34f) &&
                detail != null &&
                !detail.isEmpty()
            ) {
                canvas.drawText(
                    detail,
                    displayed.cx,
                    displayed.cy + dp(13f),
                    detailPaint
                );
            }
        }
    }

    private void relayoutNodes(int width, int height) {
        renderedNodes.clear();
        if (scene == null || scene.nodes.isEmpty() || width <= 0 || height <= 0) {
            return;
        }

        List<GraphNode> nodes = scene.nodes;
        if (scene.overview) {
            layoutOverview(nodes, width, height);
        } else {
            layoutDetail(nodes, width, height);
        }
    }

    private void layoutOverview(List<GraphNode> nodes, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f + dp(10f);
        float outerX = width * 0.29f;
        float outerY = height * 0.22f;
        if (nodes.size() == 1) {
            renderedNodes.add(
                new RenderedNode(nodes.get(0), cx, cy, radiusFor(nodes.get(0), true))
            );
            return;
        }

        for (int i = 0; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            double angle =
                (-Math.PI / 2d) +
                ((Math.PI * 2d * i) / nodes.size()) +
                organicAngleOffset(node, 0.16d);
            float ringScale = nodes.size() > 5 && (i % 2 == 1) ? 0.78f : 1f;
            float radialOffset = organicRadiusOffset(node, dp(10f));
            float nodeX =
                cx + (float) (Math.cos(angle) * ((outerX * ringScale) + radialOffset));
            float nodeY =
                cy +
                (float) (Math.sin(angle) * ((outerY * ringScale) + (radialOffset * 0.72f)));
            renderedNodes.add(
                new RenderedNode(node, nodeX, nodeY, radiusFor(node, true))
            );
        }
    }

    private void layoutDetail(List<GraphNode> nodes, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f + dp(6f);
        if (nodes.size() == 1) {
            renderedNodes.add(
                new RenderedNode(nodes.get(0), cx, cy, radiusFor(nodes.get(0), false))
            );
            return;
        }

        int[] capacities = { 6, 12, 18 };
        float[] radii = { width * 0.19f, width * 0.31f, width * 0.39f };
        int nodeIndex = 0;
        for (int ring = 0; ring < capacities.length && nodeIndex < nodes.size(); ring++) {
            int ringCount = Math.min(capacities[ring], nodes.size() - nodeIndex);
            float ringRadius = radii[ring];
            for (int i = 0; i < ringCount; i++) {
                GraphNode node = nodes.get(nodeIndex++);
                double angle =
                    (-Math.PI / 2d) +
                    ((Math.PI * 2d * i) / ringCount) +
                    organicAngleOffset(node, 0.12d);
                float radialOffset = organicRadiusOffset(node, dp(12f));
                float nodeX =
                    cx + (float) (Math.cos(angle) * (ringRadius + radialOffset));
                float nodeY =
                    cy +
                    (float) (
                        Math.sin(angle) *
                        ((ringRadius * 0.78f) + (radialOffset * 0.65f))
                    );
                renderedNodes.add(
                    new RenderedNode(node, nodeX, nodeY, radiusFor(node, false))
                );
            }
        }
    }

    private RenderedNode findTappedNode(float x, float y) {
        RenderedNode nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (RenderedNode node : renderedNodes) {
            RenderedNode displayedNode = resolveDisplayedNode(node);
            float dx = x - node.cx;
            float dy = y - node.cy;
            float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
            dx = x - displayedNode.cx;
            dy = y - displayedNode.cy;
            distance = (float) Math.sqrt((dx * dx) + (dy * dy));
            if (
                distance <= displayedNode.radius + dp(10f) &&
                distance < nearestDistance
            ) {
                nearest = displayedNode;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void startSceneAnimation() {
        if (sceneAnimator != null) {
            sceneAnimator.cancel();
        }
        sceneTransitionProgress = previousNodes.isEmpty() ? 1f : 0f;
        if (sceneTransitionProgress >= 1f) {
            invalidate();
            return;
        }
        sceneAnimator = ValueAnimator.ofFloat(0f, 1f);
        sceneAnimator.setDuration(560L);
        sceneAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        sceneAnimator.addUpdateListener(animation -> {
            sceneTransitionProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        sceneAnimator.start();
    }

    private RenderedNode resolveDisplayedNode(RenderedNode target) {
        RenderedNode previous = previousNodes.get(target.node.id);
        if (previous == null) {
            float originX = getWidth() / 2f;
            float originY = getHeight() / 2f;
            float startRadius = target.radius * 0.72f;
            return new RenderedNode(
                target.node,
                lerp(originX, target.cx, sceneTransitionProgress),
                lerp(originY, target.cy, sceneTransitionProgress),
                lerp(startRadius, target.radius, sceneTransitionProgress)
            );
        }
        return new RenderedNode(
            target.node,
            lerp(previous.cx, target.cx, sceneTransitionProgress),
            lerp(previous.cy, target.cy, sceneTransitionProgress),
            lerp(previous.radius, target.radius, sceneTransitionProgress)
        );
    }

    private double organicAngleOffset(GraphNode node, double amplitude) {
        if (node == null || node.id == null) {
            return 0d;
        }
        int hash = Math.abs(node.id.hashCode() % 1000);
        double normalized = (hash / 999d) - 0.5d;
        return normalized * amplitude;
    }

    private float organicRadiusOffset(GraphNode node, float amplitude) {
        if (node == null || node.id == null) {
            return 0f;
        }
        int hash = Math.abs((node.id.hashCode() * 31) % 1000);
        float normalized = (hash / 999f) - 0.5f;
        return normalized * amplitude;
    }

    private float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }

    private float radiusFor(GraphNode node, boolean overview) {
        float min = overview ? 36f : 30f;
        float max = overview ? 52f : 40f;
        return dp(min + ((max - min) * Math.max(0f, Math.min(1f, node.weight))));
    }

    private String fitLine(String text, float radius, TextPaint paint) {
        if (text == null) {
            return "";
        }
        float maxWidth = (radius * 1.55f);
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = builder.toString() + text.charAt(i) + ellipsis;
            if (paint.measureText(candidate) > maxWidth) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder.length() == 0 ? ellipsis : builder.append(ellipsis).toString();
    }

    private int blendColors(int first, int second) {
        return ColorUtils.blendARGB(first, second, 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static class RenderedNode {

        final GraphNode node;
        final float cx;
        final float cy;
        final float radius;

        RenderedNode(GraphNode node, float cx, float cy, float radius) {
            this.node = node;
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
        }
    }
}
