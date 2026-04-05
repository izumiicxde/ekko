package com.semantic.ekko.ui.graph;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
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
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<RenderedNode> renderedNodes = new ArrayList<>();
    private final Map<String, RenderedNode> previousNodes = new HashMap<>();
    private final ScaleGestureDetector scaleGestureDetector;
    private final Path iconPath = new Path();

    private GraphScene scene;
    private NodeTapListener nodeTapListener;
    private ValueAnimator sceneAnimator;
    private float sceneTransitionProgress = 1f;
    private float viewportOffsetX = 0f;
    private float viewportOffsetY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float downTouchX = 0f;
    private float downTouchY = 0f;
    private float scaleFactor = 1f;
    private boolean dragging = false;
    private boolean scaling = false;
    private final int touchSlop;

    public ClusterGraphView(Context context) {
        this(context, null);
    }

    public ClusterGraphView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClusterGraphView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
                    scaleFactor = clamp(scaleFactor * detector.getScaleFactor(), 0.65f, 2.2f);
                    if (Math.abs(previousScale - scaleFactor) < 0.001f) {
                        return false;
                    }

                    float focusX = detector.getFocusX();
                    float focusY = detector.getFocusY();
                    float centerX = getWidth() / 2f;
                    float centerY = getHeight() / 2f;
                    float worldX = centerX + ((focusX - centerX) / previousScale) - viewportOffsetX;
                    float worldY = centerY + ((focusY - centerY) / previousScale) - viewportOffsetY;

                    viewportOffsetX = centerX - worldX - ((centerX - focusX) / scaleFactor);
                    viewportOffsetY = centerY - worldY - ((centerY - focusY) / scaleFactor);
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

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);

        nodePaint.setStyle(Paint.Style.FILL);

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextSize(sp(12f));

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
                RenderedNode tappedNode = findTappedNode(event.getX(), event.getY());
                if (tappedNode != null && nodeTapListener != null) {
                    nodeTapListener.onNodeTapped(tappedNode.node);
                }
                return true;
            default:
                return super.onTouchEvent(event);
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
                (int) ((24 + (edge.weight * 54)) * transitionAlpha(edge.fromId + edge.toId))
            );
            edgePaint.setColor(color);
            edgePaint.setStrokeWidth(dp(1f) + (edge.weight * dp(1.35f)));
            canvas.drawLine(from.cx, from.cy, to.cx, to.cy, edgePaint);
        }
    }

    private void drawNodes(Canvas canvas) {
        for (RenderedNode rendered : renderedNodes) {
            RenderedNode displayed = applyViewport(resolveDisplayedNode(rendered));
            int fillColor = nodeFillColor(rendered.node);
            int foregroundColor = foregroundColor(fillColor);
            float alpha = transitionAlpha(rendered.node.id);
            int nodeAlpha = (int) (255f * alpha);
            float iconSize = displayed.radius * 0.42f;

            nodePaint.setColor(ColorUtils.setAlphaComponent(fillColor, nodeAlpha));
            canvas.drawCircle(displayed.cx, displayed.cy, displayed.radius, nodePaint);

            drawNodeIcon(canvas, displayed, foregroundColor, iconSize, alpha);

            titlePaint.setColor(ColorUtils.setAlphaComponent(foregroundColor, nodeAlpha));
            detailPaint.setColor(
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(foregroundColor, Color.WHITE, 0.2f),
                    (int) (208f * alpha)
                )
            );

            float titleY = displayed.cy + displayed.radius + dp(18f);
            canvas.drawText(
                fitLine(rendered.node.label, displayed.radius * 1.75f, titlePaint),
                displayed.cx,
                titleY + verticalTextOffset(titlePaint),
                titlePaint
            );
            if (rendered.node.detail != null && !rendered.node.detail.isEmpty()) {
                canvas.drawText(
                    fitLine(rendered.node.detail, displayed.radius * 1.9f, detailPaint),
                    displayed.cx,
                    titleY + dp(14f) + verticalTextOffset(detailPaint),
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

        if (scene.overview) {
            layoutOverview(scene.nodes, width, height);
        } else {
            layoutDetail(scene.nodes, width, height);
        }
        relaxOverlaps(width, height, scene.overview);
    }

    private void layoutOverview(List<GraphNode> nodes, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        float outerX = width * 0.48f;
        float outerY = height * 0.42f;
        int startIndex = 0;
        if (!nodes.isEmpty() && nodes.get(0).type == GraphNode.TYPE_HUB) {
            GraphNode hub = nodes.get(0);
            renderedNodes.add(new RenderedNode(hub, cx, cy, radiusFor(hub, true)));
            startIndex = 1;
            outerX = width * 0.56f;
            outerY = height * 0.48f;
        }

        int ringCount = Math.max(1, nodes.size() - startIndex);
        for (int i = startIndex; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            int ringIndex = i - startIndex;
            double angle =
                (-Math.PI / 2d) +
                ((Math.PI * 2d * ringIndex) / ringCount) +
                organicAngleOffset(node, 0.12d);
            float radialOffset = organicRadiusOffset(node, dp(18f));
            float nodeX = cx + (float) (Math.cos(angle) * (outerX + radialOffset));
            float nodeY = cy + (float) (Math.sin(angle) * (outerY + (radialOffset * 0.72f)));
            renderedNodes.add(new RenderedNode(node, nodeX, nodeY, radiusFor(node, true)));
        }
    }

    private void layoutDetail(List<GraphNode> nodes, int width, int height) {
        float cx = width / 2f;
        float cy = height / 2f;
        int startIndex = 0;
        if (!nodes.isEmpty() && nodes.get(0).type == GraphNode.TYPE_HUB) {
            GraphNode hub = nodes.get(0);
            renderedNodes.add(new RenderedNode(hub, cx, cy, radiusFor(hub, false)));
            startIndex = 1;
        }

        int remaining = Math.max(0, nodes.size() - startIndex);
        int nodeIndex = startIndex;
        int ring = 0;
        float minDimension = Math.min(width, height);
        while (remaining > 0) {
            int ringCount = Math.min(remaining, 8 + (ring * 4));
            float ringRadius = (minDimension * 0.34f) + (ring * minDimension * 0.18f);
            for (int i = 0; i < ringCount && nodeIndex < nodes.size(); i++) {
                GraphNode node = nodes.get(nodeIndex++);
                double angle =
                    (-Math.PI / 2d) +
                    ((Math.PI * 2d * i) / ringCount) +
                    organicAngleOffset(node, 0.18d);
                float radialOffset = organicRadiusOffset(node, dp(18f));
                float nodeX = cx + (float) (Math.cos(angle) * (ringRadius + radialOffset));
                float nodeY = cy + (float) (Math.sin(angle) * (ringRadius + radialOffset));
                renderedNodes.add(new RenderedNode(node, nodeX, nodeY, radiusFor(node, false)));
            }
            remaining -= ringCount;
            ring++;
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
            if (distance <= displayedNode.radius + dp(10f) && distance < nearestDistance) {
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
        sceneAnimator.setDuration(420L);
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
            float startRadius = target.radius * 0.55f;
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
        float floatX = floatingOffset(node.node, 0.82f, dp(2f));
        float floatY = floatingOffset(node.node, 0.53f, dp(2f));
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
        float distance = dp(36f) + (seedB * dp(72f));
        double angle = (seedA * Math.PI * 2d) + organicAngleOffset(target.node, 0.6d);
        float startX = target.cx + (float) (Math.cos(angle) * distance);
        float startY = target.cy + (float) (Math.sin(angle) * distance);
        return new RenderedNode(target.node, startX, startY, target.radius * 0.7f);
    }

    private float floatingOffset(GraphNode node, float speedScale, float amplitude) {
        long now = SystemClock.uptimeMillis();
        float phase = normalizedHash(node, 91) * (float) (Math.PI * 2d);
        float drift = (now / (2400f - (normalizedHash(node, 57) * 500f))) * speedScale;
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
        float iconSize,
        float alpha
    ) {
        iconPaint.setColor(ColorUtils.setAlphaComponent(foregroundColor, (int) (236f * alpha)));
        iconPaint.setStrokeWidth(dp(1.8f));
        iconPaint.setStyle(Paint.Style.STROKE);

        float cx = displayed.cx;
        float cy = displayed.cy;

        if (displayed.node.type == GraphNode.TYPE_FOLDER) {
            float left = cx - (iconSize * 0.72f);
            float top = cy - (iconSize * 0.34f);
            float right = cx + (iconSize * 0.72f);
            float bottom = cy + (iconSize * 0.5f);
            float tabRight = cx - (iconSize * 0.08f);
            iconPath.reset();
            iconPath.moveTo(left, top + (iconSize * 0.12f));
            iconPath.lineTo(left + (iconSize * 0.22f), top - (iconSize * 0.14f));
            iconPath.lineTo(tabRight, top - (iconSize * 0.14f));
            iconPath.lineTo(tabRight + (iconSize * 0.18f), top + (iconSize * 0.12f));
            iconPath.lineTo(right, top + (iconSize * 0.12f));
            iconPath.lineTo(right, bottom);
            iconPath.lineTo(left, bottom);
            iconPath.close();
            canvas.drawPath(iconPath, iconPaint);
            return;
        }

        if (displayed.node.type == GraphNode.TYPE_DOCUMENT) {
            float left = cx - (iconSize * 0.54f);
            float top = cy - (iconSize * 0.72f);
            float right = cx + (iconSize * 0.42f);
            float bottom = cy + (iconSize * 0.68f);
            float fold = iconSize * 0.28f;
            iconPath.reset();
            iconPath.moveTo(left, top);
            iconPath.lineTo(right - fold, top);
            iconPath.lineTo(right, top + fold);
            iconPath.lineTo(right, bottom);
            iconPath.lineTo(left, bottom);
            iconPath.close();
            canvas.drawPath(iconPath, iconPaint);
            canvas.drawLine(right - fold, top, right - fold, top + fold, iconPaint);
            canvas.drawLine(right - fold, top + fold, right, top + fold, iconPaint);
            return;
        }

        float orbitRadius = iconSize * 0.62f;
        float dotRadius = iconSize * 0.1f;
        float topY = cy - orbitRadius;
        float leftX = cx - (orbitRadius * 0.86f);
        float rightX = cx + (orbitRadius * 0.86f);
        float bottomY = cy + (orbitRadius * 0.5f);
        canvas.drawLine(leftX, bottomY, cx, topY, iconPaint);
        canvas.drawLine(cx, topY, rightX, bottomY, iconPaint);
        canvas.drawLine(leftX, bottomY, rightX, bottomY, iconPaint);
        iconPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, topY, dotRadius, iconPaint);
        canvas.drawCircle(leftX, bottomY, dotRadius, iconPaint);
        canvas.drawCircle(rightX, bottomY, dotRadius, iconPaint);
    }

    private int nodeFillColor(GraphNode node) {
        if (node.type == GraphNode.TYPE_HUB) {
            return ColorUtils.blendARGB(node.color, Color.WHITE, 0.18f);
        }
        if (node.type == GraphNode.TYPE_DOCUMENT) {
            return ColorUtils.blendARGB(node.color, Color.parseColor("#0F172A"), 0.16f);
        }
        return ColorUtils.blendARGB(node.color, Color.WHITE, 0.06f);
    }

    private int foregroundColor(int backgroundColor) {
        return ColorUtils.calculateLuminance(backgroundColor) > 0.45d
            ? Color.parseColor("#0F172A")
            : Color.WHITE;
    }

    private int blendColors(int first, int second) {
        return ColorUtils.blendARGB(first, second, 0.5f);
    }

    private void relaxOverlaps(int width, int height, boolean overview) {
        if (renderedNodes.size() < 2) {
            return;
        }
        float centerX = width / 2f;
        float centerY = height / 2f;
        float maxRadius = Math.min(width, height) * (overview ? 0.66f : 1.35f);
        for (int pass = 0; pass < 40; pass++) {
            boolean changed = false;
            for (int i = 0; i < renderedNodes.size(); i++) {
                RenderedNode left = renderedNodes.get(i);
                if (left.node.type == GraphNode.TYPE_HUB) {
                    continue;
                }
                float adjustX = 0f;
                float adjustY = 0f;
                for (int j = i + 1; j < renderedNodes.size(); j++) {
                    RenderedNode right = renderedNodes.get(j);
                    if (right.node.type == GraphNode.TYPE_HUB) {
                        continue;
                    }
                    float dx = right.cx - left.cx;
                    float dy = right.cy - left.cy;
                    float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
                    float minDistance = (left.radius + right.radius) + dp(52f);
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
                float centerDistance = (float) Math.sqrt(
                    (fromCenterX * fromCenterX) + (fromCenterY * fromCenterY)
                );
                if (centerDistance > maxRadius && centerDistance > 0f) {
                    float scale = maxRadius / centerDistance;
                    nextX = centerX + (fromCenterX * scale);
                    nextY = centerY + (fromCenterY * scale);
                }

                renderedNodes.set(i, new RenderedNode(left.node, nextX, nextY, left.radius));
            }
            if (!changed) {
                break;
            }
        }
    }

    private float radiusFor(GraphNode node, boolean overview) {
        if (node.type == GraphNode.TYPE_HUB) {
            return dp(overview ? 34f : 30f);
        }
        float min = overview ? 22f : 20f;
        float max = overview ? 34f : 28f;
        return dp(min + ((max - min) * Math.max(0f, Math.min(1f, node.weight))));
    }

    private String fitLine(String text, float radius, TextPaint paint) {
        if (text == null) {
            return "";
        }
        float maxWidth = radius * 1.7f;
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

    private float verticalTextOffset(Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return -((metrics.ascent + metrics.descent) / 2f);
    }

    private float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }

    private float transitionAlpha(String key) {
        return 0.22f + (0.78f * staggeredProgress(key));
    }

    private float staggeredProgress(String key) {
        float seed = normalizedHashFromKey(key, 131);
        float start = seed * 0.18f;
        return clamp((sceneTransitionProgress - start) / (1f - start), 0f, 1f);
    }

    private float normalizedHashFromKey(String key, int salt) {
        if (key == null) {
            return 0.5f;
        }
        int hash = Math.abs((key.hashCode() * 31) + salt);
        return (hash % 1000) / 999f;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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
