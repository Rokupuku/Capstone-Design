package com.autoreadme.client.github.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepositoryResponse(
        @JsonProperty("default_branch")
        String defaultBranch
) {}
