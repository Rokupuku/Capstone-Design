package com.autoreadme.client.github.model;

import java.util.List;

public record GitTreeResponse(
        String sha,
        List<GitTreeItem> tree,
        boolean truncated
) {}