package com.autoreadme.client.github.model;

public record GitHubFileResponse(
        String path,
        String content
) {}