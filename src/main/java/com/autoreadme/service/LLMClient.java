package com.autoreadme.service;

import reactor.core.publisher.Mono;

public interface LLMClient {
    Mono<String> generateReadme(String context, String userDescription);
}
