package com.semantic.ekko.ui.graph;

import java.util.Collections;
import java.util.List;

public class GraphScene {

    public final boolean overview;
    public final List<GraphNode> nodes;
    public final List<GraphEdge> edges;

    public GraphScene(
        boolean overview,
        List<GraphNode> nodes,
        List<GraphEdge> edges
    ) {
        this.overview = overview;
        this.nodes = nodes == null ? Collections.emptyList() : nodes;
        this.edges = edges == null ? Collections.emptyList() : edges;
    }
}
