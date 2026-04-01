package com.autoreadme.api.dto;

public record AnalyzeStartRequest(
        String githubUrl,
        String branch
) {}
