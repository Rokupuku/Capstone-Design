package com.autoreadme.api.dto;

public record AnalyzeResult(
        String markdown,
        AnalyzeGraph graph
) {}
