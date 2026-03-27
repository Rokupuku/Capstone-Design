package com.autoreadme.client.github.model;

public record GitTreeItem(
        String path,
        String mode,
        String type,
        String sha,
        String url
) {}