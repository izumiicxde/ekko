package com.semantic.ekko.ui.graph;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
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
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<RenderedNode> renderedNodes = new ArrayList<>();
    private final Map<String, RenderedNode> previousNodes = new HashMap<>();

    private GraphScene scene;
    private NodeTapListener nodeTapListener;
    private ValueAnimator sceneAnimator;
    private float sceneTransitionProgress = 1f;
    private float viewportOffsetX = 0f;
    private float viewportOffsetY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private boolean dragging = false;
    private float downTouchX = 0f;
    private float downTouchY = 0f;
    private int touchSlop = 0;
    private float scaleFactor = 1f;
    private final ScaleGestureDetector scaleGestureDetector;
    private boolean scaling = false;
    private final Path fileIconPath = new Path();

    public ClusterGraphView(Context context) {
        super(context);
        scaleGestureDetector = createScaleGestureDetector(context);
        init();
    }

    public ClusterGraphView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scaleGestureDetector = createScaleGestureDetector(context);
        init();
    }

    public ClusterGraphView(
        Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        scaleGestureDetector = createScaleGestureDetector(context);
        init();
    }

    private ScaleGestureDetector createScaleGestureDetector(Context context) {
        return new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    scaling = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float previousScale = scaleFactor;
                    scaleFactor = clamp(
                        scaleFactor * detector.getScaleFactor(),
                        0.72f,
                        1.9f
                    );
                    if (Math.abs(previousScale - scaleFactor) < 0.001f) {
                        return false;
                    }

                    float focusX = detector.getFocusX();
                    float focusY = detector.getFocusY();
                    float centerX = getWidth() / 2f;
                    float centerY = getHeight() / 2f;
                    float worldX =
                        centerX +
                        ((focusX - centerX) / previousScale) -
                        viewportOffsetX;
                    float worldY =
                        centerY +
                        ((focusY - centerY) / previousScale) -
                        viewportOffsetY;

                    viewportOffsetX =
                        centerX -
                        worldX -
                        ((centerX - focusX) / scaleFactor);
                    viewportOffsetY =
                        centerY -
                        worldY -
                        ((centerY - focusY) / scaleFactor);
                    invalidate();
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    scaling = false;
                }
            }
        );
    }

    private void init() {
        setWillNotDraw(false);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.6f));
        strokePaint.setColor(Color.argb(28, 15, 23, 42));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(Color.argb(20, 32, 84, 215));

        labelPaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextSize(sp(12.5f));

        detailPaint.setTextAlign(Paint.Align.CENTER);
        detailPaint.setTextSize(sp(10.5f));
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
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTouchX = event.getX();
                downTouchY = event.getY();
                lastTouchX = downTouchX;
                lastTouchY = downTouchY;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaling || event.getPointerCount() > 1) {
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    return true;
                }
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                if (
                    dragging ||
                    Math.abs(event.getX() - downTouchX) > touchSlop ||
                    Math.abs(event.getY() - downTouchY) > touchSlop
                ) {
                    dragging = true;
                    viewportOffsetX += dx / scaleFactor;
                    viewportOffsetY += dy / scaleFactor;
                    invalidate();
                }
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging || scaling || renderedNodes.isEmpty()) {
                    dragging = false;
                    scaling = false;
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                RenderedNode tappedNode = findTappedNode(x, y);
                if (tappedNode != null && nodeTapListener != null) {
                    nodeTapListener.onNodeTapped(tappedNode.node);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
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

        float step = dp(48f) * scaleFactor;
        float offsetX = viewportOffsetX * scaleFactor;
        float offsetY = viewportOffsetY * scaleFactor;
        float startX = inset + mod(offsetX, step) - step;
        for (float x = startX; x < getWidth() - inset + step; x += step) {
            canvas.drawLine(x, inset, x, getHeight() - inset, gridPaint);
        }
        float startY = inset + mod(offsetY, step) - step;
        for (float y = startY; y < getHeight() - inset + step; y += step) {
            canvas.drawLine(inset, y, getWidth() - inset, y, gridPaint);
        }
    }

    private void drawEdges(Canvas canvas) {
        Map<String, RenderedNode> index = new HashMap<>();
        for (RenderedNode node : renderedNodes) {
            index.put(node.node.id, applyViewport(resolveDisplayedNode(node)));
        }
        for (GraphEdge edge : scene.edges) {
            RenderedNode from = index.get(edge.fromId);
            RenderedNode to = index.get(edge.toId);
            if (from == null || to == null) {
                continue;
            }
            int color = ColorUtils.setAlphaComponent(
                blendColors(from.node.color, to.node.color),
                (int) ((18 + (edge.weight * 78)) * transitionAlpha(edge.fromId + edge.toId))
            );
            edgePaint.setColor(color);
            edgePaint.setStrokeWidth(dp(1.5f) + (edge.weight * dp(2.1f)));
            canvas.drawLine(from.cx, from.cy, to.cx, to.cy, edgePaint);
        }
    }

    private void drawNodes(Canvas canvas) {
        for (RenderedNode rendered : renderedNodes) {
            RenderedNode displayed = applyViewport(resolveDisplayedNode(rendered));
            int fillColor = nodeFillColor(rendered.node);
            int foregroundColor = foregroundColor(fillColor);
            int detailColor = ColorUtils.setAlphaComponent(foregroundColor, 210);
            float alpha = transitionAlpha(rendered.node.id);
            int nodeAlpha = (int) (255f * alpha);
            int labelAlpha = (int) (34f * alpha);
            float iconRadius = displayed.radius * 0.26f;
            float labelWidth = Math.min(
                displayed.radius * 1.42f,
                Math.max(
                    displayed.radius * 0.92f,
                    titlePaint.measureText(rendered.node.label) + dp(18f)
                )
            );
            float labelHeight = dp(20f);
            float labelCenterY = displayed.cy + (displayed.radius * 0.46f);
            nodePaint.setColor(ColorUtils.setAlphaComponent(fillColor, nodeAlpha));
            canvas.drawCircle(
                displayed.cx,
                displayed.cy,
                displayed.radius,
                nodePaint
            );
            nodePaint.setColor(
                ColorUtils.setAlphaComponent(
                    Color.WHITE,
                    (int) (28f * alpha)
                )
            );
            canvas.drawCircle(
                displayed.cx,
                displayed.cy - (displayed.radius * 0.24f),
                displayed.radius * 0.68f,
                nodePaint
            );
            strokePaint.setColor(
                ColorUtils.setAlphaComponent(Color.argb(28, 15, 23, 42), nodeAlpha)
            );
            canvas.drawCircle(
                displayed.cx,
                displayed.cy,
                displayed.radius,
                strokePaint
            );

            drawNodeIcon(canvas, displayed, foregroundColor, iconRadius, alpha);

            labelPaint.setColor(ColorUtils.setAlphaComponent(foregroundColor, labelAlpha));
            canvas.drawRoundRect(
                displayed.cx - (labelWidth / 2f),
                labelCenterY - (labelHeight / 2f),
                displayed.cx + (labelWidth / 2f),
                labelCenterY + (labelHeight / 2f),
                labelHeight / 2f,
                labelHeight / 2f,
                labelPaint
            );

            titlePaint.setColor(ColorUtils.setAlphaComponent(foregroundColor, nodeAlpha));
            detailPaint.setColor(ColorUtils.setAlphaComponent(detailColor, nodeAlpha));

            String title = fitLine(
                rendered.node.label,
                displayed.radius * 0.95f,
                titlePaint
            );
            String detail = fitLine(
                rendered.node.detail,
                displayed.radius * 0.94f,
                detailPaint
            );
            canvas.drawText(
                title,
                displayed.cx,
                labelCenterY + verticalTextOffset(titlePaint),
                titlePaint
            );
            if (
                displayed.radius >= dp(40f) &&
                detail != null &&
                !detail.isEmpty()
            ) {
                canvas.drawText(
                    detail,
                    displayed.cx,
                    displayed.cy + (displayed.radius * 0.73f),
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
        relaxOverlaps(width, height, scene.overview);
    }

    private void layoutOverview(List<GraphNode> nodes, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f + dp(10f);
        float outerX = width * 0.37f;
        float outerY = height * 0.3f;
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
            float ringScale = nodes.size() > 5 && (i % 2 == 1) ? 0.9f : 1.1f;
            float radialOffset = organicRadiusOffset(node, dp(24f));
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

        int[] capacities = { 5, 8, 11, 15, 18 };
        float minDimension = Math.min(width, height);
        float[] radii = {
            minDimension * 0.24f,
            minDimension * 0.37f,
            minDimension * 0.5f,
            minDimension * 0.63f,
            minDimension * 0.76f
        };
        int nodeIndex = 0;
        for (int ring = 0; ring < capacities.length && nodeIndex < nodes.size(); ring++) {
            int ringCount = Math.min(capacities[ring], nodes.size() - nodeIndex);
            float ringRadius = radii[ring];
            for (int i = 0; i < ringCount; i++) {
                GraphNode node = nodes.get(nodeIndex++);
                double angle =
                    (-Math.PI / 2d) +
                    ((Math.PI * 2d * i) / ringCount) +
                    organicAngleOffset(node, 0.22d);
                float radialOffset = organicRadiusOffset(node, dp(28f));
                float nodeX =
                    cx + (float) (Math.cos(angle) * (ringRadius + radialOffset));
                float nodeY =
                    cy +
                    (float) (
                        Math.sin(angle) *
                        ((ringRadius * 0.94f) + (radialOffset * 0.76f))
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
            RenderedNode displayedNode = applyViewport(resolveDisplayedNode(node));
            float dx = x - displayedNode.cx;
            float dy = y - displayedNode.cy;
            float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
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
            float progress = staggeredProgress(target.node.id);
            RenderedNode origin = randomSpawnNode(target);
            float startRadius = target.radius * 0.58f;
            return new RenderedNode(
                target.node,
                lerp(origin.cx, target.cx, progress),
                lerp(origin.cy, target.cy, progress),
                lerp(startRadius, target.radius, progress)
            );
        }
        float progress = staggeredProgress(target.node.id);
        return new RenderedNode(
            target.node,
            lerp(previous.cx, target.cx, progress),
            lerp(previous.cy, target.cy, progress),
            lerp(previous.radius, target.radius, progress)
        );
    }

    private RenderedNode applyViewport(RenderedNode node) {
        float floatX = floatingOffset(node.node, 0.82f, dp(8f));
        float floatY = floatingOffset(node.node, 0.53f, dp(10f));
        float worldX = node.cx + viewportOffsetX + floatX;
        float worldY = node.cy + viewportOffsetY + floatY;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        return new RenderedNode(
            node.node,
            centerX + ((worldX - centerX) * scaleFactor),
            centerY + ((worldY - centerY) * scaleFactor),
            node.radius * scaleFactor
        );
    }

    private RenderedNode randomSpawnNode(RenderedNode target) {
        float seedA = normalizedHash(target.node, 17);
        float seedB = normalizedHash(target.node, 43);
        float distance = dp(42f) + (seedB * dp(86f));
        double angle = (seedA * Math.PI * 2d) + organicAngleOffset(target.node, 0.7d);
        float startX = target.cx + (float) (Math.cos(angle) * distance);
        float startY = target.cy + (float) (Math.sin(angle) * distance);
        return new RenderedNode(target.node, startX, startY, target.radius * 0.72f);
    }

    private float floatingOffset(GraphNode node, float speedScale, float amplitude) {
        long now = SystemClock.uptimeMillis();
        float phase = normalizedHash(node, 91) * (float) (Math.PI * 2d);
        float drift =
            (now / (2200f - (normalizedHash(node, 57) * 600f))) *
            speedScale;
        return (float) Math.sin(phase + drift) * amplitude;
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

    private float normalizedHash(GraphNode node, int salt) {
        if (node == null || node.id == null) {
            return 0.5f;
        }
        int hash = Math.abs((node.id.hashCode() * 31) + salt);
        return (hash % 1000) / 999f;
    }

    private void drawNodeIcon(
        Canvas canvas,
        RenderedNode displayed,
        int foregroundColor,
        float iconRadius,
        float alpha
    ) {
        iconPaint.setColor(
            ColorUtils.setAlphaComponent(foregroundColor, (int) (236f * alpha))
        );
        iconPaint.setStrokeWidth(dp(1.8f));
        iconPaint.setStyle(Paint.Style.STROKE);
        float centerY = displayed.cy - (displayed.radius * 0.18f);
        if (displayed.node.type == GraphNode.TYPE_CLUSTER) {
            float r = iconRadius * 0.22f;
            float leftX = displayed.cx - (iconRadius * 0.72f);
            float rightX = displayed.cx + (iconRadius * 0.72f);
            float bottomY = centerY + (iconRadius * 0.62f);
            float topY = centerY - (iconRadius * 0.72f);
            canvas.drawLine(leftX, bottomY, displayed.cx, topY, iconPaint);
            canvas.drawLine(displayed.cx, topY, rightX, bottomY, iconPaint);
            canvas.drawLine(leftX, bottomY, rightX, bottomY, iconPaint);
            iconPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(leftX, bottomY, r, iconPaint);
            canvas.drawCircle(displayed.cx, topY, r, iconPaint);
            canvas.drawCircle(rightX, bottomY, r, iconPaint);
        } else {
            iconPaint.setStyle(Paint.Style.STROKE);
            float left = displayed.cx - (iconRadius * 0.68f);
            float top = centerY - (iconRadius * 0.8f);
            float right = displayed.cx + (iconRadius * 0.52f);
            float bottom = centerY + (iconRadius * 0.82f);
            float fold = iconRadius * 0.32f;
            fileIconPath.reset();
            fileIconPath.moveTo(left, top);
            fileIconPath.lineTo(right - fold, top);
            fileIconPath.lineTo(right, top + fold);
            fileIconPath.lineTo(right, bottom);
            fileIconPath.lineTo(left, bottom);
            fileIconPath.close();
            canvas.drawPath(fileIconPath, iconPaint);
            canvas.drawLine(right - fold, top, right - fold, top + fold, iconPaint);
            canvas.drawLine(right - fold, top + fold, right, top + fold, iconPaint);
            float lineLeft = left + (iconRadius * 0.22f);
            float lineRight = right - (iconRadius * 0.2f);
            canvas.drawLine(
                lineLeft,
                centerY - (iconRadius * 0.08f),
                lineRight,
                centerY - (iconRadius * 0.08f),
                iconPaint
            );
            canvas.drawLine(
                lineLeft,
                centerY + (iconRadius * 0.34f),
                right - (iconRadius * 0.34f),
                centerY + (iconRadius * 0.34f),
                iconPaint
            );
        }
    }

    private int nodeFillColor(GraphNode node) {
        if (node.type == GraphNode.TYPE_CLUSTER) {
            return ColorUtils.blendARGB(node.color, Color.WHITE, 0.05f);
        }
        return ColorUtils.blendARGB(node.color, Color.parseColor("#0F172A"), 0.12f);
    }

    private int foregroundColor(int backgroundColor) {
        return ColorUtils.calculateLuminance(backgroundColor) > 0.45d
            ? Color.parseColor("#0F172A")
            : Color.WHITE;
    }

    private float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }

    private float transitionAlpha(String key) {
        return 0.2f + (0.8f * staggeredProgress(key));
    }

    private float staggeredProgress(String key) {
        float seed = normalizedHashFromKey(key, 131);
        float start = seed * 0.24f;
        return clamp((sceneTransitionProgress - start) / (1f - start), 0f, 1f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float mod(float value, float divisor) {
        float result = value % divisor;
        return result < 0f ? result + divisor : result;
    }

    private float normalizedHashFromKey(String key, int salt) {
        if (key == null) {
            return 0.5f;
        }
        int hash = Math.abs((key.hashCode() * 31) + salt);
        return (hash % 1000) / 999f;
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

    private void relaxOverlaps(int width, int height, boolean overview) {
        if (renderedNodes.size() < 2) {
            return;
        }
        float centerX = width / 2f;
        float centerY = (height / 2f) + (overview ? dp(10f) : dp(6f));
        float maxRadius = Math.min(width, height) * (overview ? 0.44f : 0.88f);
        for (int pass = 0; pass < 18; pass++) {
            boolean changed = false;
            for (int i = 0; i < renderedNodes.size(); i++) {
                RenderedNode left = renderedNodes.get(i);
                float adjustX = 0f;
                float adjustY = 0f;
                for (int j = i + 1; j < renderedNodes.size(); j++) {
                    RenderedNode right = renderedNodes.get(j);
                    float dx = right.cx - left.cx;
                    float dy = right.cy - left.cy;
                    float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
                    float minDistance = (left.radius + right.radius) + dp(18f);
                    if (distance <= 0.001f) {
                        dx = dp(1f);
                        dy = dp(1f);
                        distance = dp(1f);
                    }
                    if (distance < minDistance) {
                        float push = (minDistance - distance) * 0.5f;
                        float nx = dx / distance;
                        float ny = dy / distance;
                        adjustX -= nx * push;
                        adjustY -= ny * push;
                        renderedNodes.set(
                            j,
                            new RenderedNode(
                                right.node,
                                right.cx + (nx * push),
                                right.cy + (ny * push),
                                right.radius
                            )
                        );
                        changed = true;
                    }
                }
                float nextX = left.cx + adjustX;
                float nextY = left.cy + adjustY;
                float fromCenterX = nextX - centerX;
                float fromCenterY = nextY - centerY;
                float centerDistance =
                    (float) Math.sqrt((fromCenterX * fromCenterX) + (fromCenterY * fromCenterY));
                if (centerDistance > maxRadius && centerDistance > 0f) {
                    float scale = maxRadius / centerDistance;
                    nextX = centerX + (fromCenterX * scale);
                    nextY = centerY + (fromCenterY * scale);
                }
                renderedNodes.set(
                    i,
                    new RenderedNode(left.node, nextX, nextY, left.radius)
                );
            }
            if (!changed) {
                break;
            }
        }
    }

    private float verticalTextOffset(Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return -((metrics.ascent + metrics.descent) / 2f);
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
