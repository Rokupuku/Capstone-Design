package com.autoreadme.client.github;

import com.autoreadme.client.github.model.GitTreeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
public class GitHubClient {

    private final WebClient webClient;

    @Value("${github.api.token}")
    private String githubToken;

    public GitHubClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    public Mono<GitTreeResponse> getTree(String owner, String repo, String branch) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .header("Authorization", "Bearer " + githubToken)
                .retrieve()
                .bodyToMono(GitTreeResponse.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }

    public Mono<String> getFileContent(String owner, String repo, String path) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3.raw")
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }
}