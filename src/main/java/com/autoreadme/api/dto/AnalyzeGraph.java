package com.autoreadme.api.dto;

import java.util.List;

public record AnalyzeGraph(
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {}
