package com.semantic.ekko.ui.graph;

public class GraphNode {

    public static final int TYPE_CLUSTER = 0;
    public static final int TYPE_DOCUMENT = 1;

    public final String id;
    public final String label;
    public final String detail;
    public final float weight;
    public final int color;
    public final int type;
    public final long documentId;

    public GraphNode(
        String id,
        String label,
        String detail,
        float weight,
        int color,
        int type,
        long documentId
    ) {
        this.id = id;
        this.label = label;
        this.detail = detail;
        this.weight = weight;
        this.color = color;
        this.type = type;
        this.documentId = documentId;
    }
}
