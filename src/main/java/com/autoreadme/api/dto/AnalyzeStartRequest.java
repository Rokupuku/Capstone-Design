package com.autoreadme.api.dto;

public record AnalyzeStartRequest(
        String githubUrl,
        String branch,
        String projectDescription,
        /** README 구성 스타일: standard | minimal | detailed */
        String template,
        /** 생성 문서 언어: ko | en | ja */
        String language
) {}
