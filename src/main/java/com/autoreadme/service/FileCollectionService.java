package com.autoreadme.service;

import com.autoreadme.client.github.GitHubClient;
import com.autoreadme.client.github.model.GitHubFileResponse;
import com.autoreadme.client.github.model.GitTreeItem;
import com.autoreadme.client.github.model.GitTreeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileCollectionService {

    private final GitHubClient gitHubClient;

    public Flux<GitHubFileResponse> collectTargetFiles(String owner, String repo, String branch) {
        log.info("Starting file collection for {}/{} (branch: {})", owner, repo, branch);

        return gitHubClient.getTree(owner, repo, branch)
                .flatMapMany(response -> {
                    List<String> targetPaths = filterTargetFiles(response);
                    log.info("Found {} target files to download.", targetPaths.size());
                    return Flux.fromIterable(targetPaths);
                })
                .delayElements(Duration.ofMillis(100)) // Rate limiting precaution
                .flatMap(path -> gitHubClient.getFileContent(owner, repo, path)
                        .map(content -> new GitHubFileResponse(path, content))
                        .onErrorResume(e -> {
                            log.error("Failed to download file {}: {}", path, e.getMessage());
                            return Mono.empty();
                        })
                );
    }

    private List<String> filterTargetFiles(GitTreeResponse response) {
        if (response == null || response.tree() == null) {
            return List.of();
        }

        return response.tree().stream()
                .filter(item -> "blob".equals(item.type()))
                .map(GitTreeItem::path)
                .filter(this::isInterestingFile)
                .toList();
    }

    private boolean isInterestingFile(String path) {
        return path.endsWith("build.gradle") ||
                path.endsWith("package.json") ||
                path.endsWith("pom.xml") ||
                path.endsWith("Controller.java") ||
                path.endsWith("Entity.java") ||
                path.endsWith("VO.java") ||
                path.endsWith("Repository.java") ||
                path.endsWith("Service.java");
    }
}