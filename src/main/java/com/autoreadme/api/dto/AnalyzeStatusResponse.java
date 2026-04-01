package com.autoreadme.api.dto;

public record AnalyzeStatusResponse(
        String status,
        String jobId,
        String stage,
        int stageProgress,
        String error,
        AnalyzeResult result
) {}
