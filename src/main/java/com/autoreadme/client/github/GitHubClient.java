package com.autoreadme.client.github;

import com.autoreadme.client.github.model.GitTreeResponse;
import com.autoreadme.client.github.model.RepositoryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
public class GitHubClient {

    private final WebClient webClient;

    // 테스트/로컬에서 토큰이 미설정일 수 있으므로 기본값을 비워 둡니다.
    // 실제 실행 시에는 API 호출 직전에 토큰 존재 여부를 검증하도록 처리합니다.
    @Value("${github.api.token:}")
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
                .headers(headers -> addAuthHeader(headers, githubToken))
                .retrieve()
                .bodyToMono(GitTreeResponse.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }

    public Mono<String> getFileContent(String owner, String repo, String path) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .headers(headers -> addAuthHeader(headers, githubToken))
                .header("Accept", "application/vnd.github.v3.raw")
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));
    }

    public Mono<String> getDefaultBranch(String owner, String repo) {
        return webClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .headers(headers -> addAuthHeader(headers, githubToken))
                .retrieve()
                .bodyToMono(RepositoryResponse.class)
                .map(RepositoryResponse::defaultBranch)
                .map(branch -> (branch == null || branch.isBlank()) ? "main" : branch);
    }

    public boolean hasGitHubToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    private static void addAuthHeader(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }
}