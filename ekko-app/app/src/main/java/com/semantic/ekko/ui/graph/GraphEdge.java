package com.semantic.ekko.ui.graph;

public class GraphEdge {

    public final String fromId;
    public final String toId;
    public final float weight;

    public GraphEdge(String fromId, String toId, float weight) {
        this.fromId = fromId;
        this.toId = toId;
        this.weight = weight;
    }
}
