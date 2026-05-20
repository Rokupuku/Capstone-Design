package com.autoreadme.service;

import com.autoreadme.api.dto.DetectedStack;
import com.autoreadme.api.dto.TechStack;
import com.autoreadme.client.github.model.GitHubFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StackDetector {

    public Mono<List<DetectedStack>> detectStacks(Flux<GitHubFileResponse> files) {
        return files.collectList()
                .map(this::analyzeFiles);
    }

    private List<DetectedStack> analyzeFiles(List<GitHubFileResponse> files) {
        Set<DetectedStack> detected = new HashSet<>();

        for (GitHubFileResponse file : files) {
            String path = file.path().toLowerCase();
            String content = file.content();

            // JVM 기반 식별
            if (path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) {
                detected.add(createStack(TechStack.GRADLE, "Build Tool"));
                if (content.contains("org.springframework.boot")) {
                    detected.add(createStack(TechStack.SPRING_BOOT, "Framework"));
                }
                if (content.contains("kotlin")) {
                    detected.add(createStack(TechStack.KOTLIN, "Language"));
                } else {
                    detected.add(createStack(TechStack.JAVA, "Language"));
                }
                if (content.contains("mysql")) detected.add(createStack(TechStack.MYSQL, "Database"));
                if (content.contains("postgresql")) detected.add(createStack(TechStack.POSTGRESQL, "Database"));
                if (content.contains("h2")) detected.add(createStack(TechStack.H2, "Database"));
                if (content.contains("redis")) detected.add(createStack(TechStack.REDIS, "Database"));
            }

            if (path.endsWith("pom.xml")) {
                detected.add(createStack(TechStack.MAVEN, "Build Tool"));
                detected.add(createStack(TechStack.JAVA, "Language"));
                if (content.contains("spring-boot")) {
                    detected.add(createStack(TechStack.SPRING_BOOT, "Framework"));
                }
            }

            // Node.js 기반 식별
            if (path.endsWith("package.json")) {
                detected.add(createStack(TechStack.NPM, "Build Tool"));
                if (content.contains("\"react\"")) detected.add(createStack(TechStack.REACT, "Framework"));
                if (content.contains("\"next\"")) detected.add(createStack(TechStack.NEXT_JS, "Framework"));
                if (content.contains("\"vue\"")) detected.add(createStack(TechStack.VUE, "Framework"));
                if (content.contains("\"express\"")) detected.add(createStack(TechStack.EXPRESS, "Framework"));
                if (content.contains("\"typescript\"")) {
                    detected.add(createStack(TechStack.TYPESCRIPT, "Language"));
                } else {
                    detected.add(createStack(TechStack.JAVASCRIPT, "Language"));
                }
            }

            // Python 기반 식별
            if (path.endsWith("requirements.txt") || path.endsWith("pyproject.toml")) {
                detected.add(createStack(TechStack.PYTHON, "Language"));
                if (content.contains("django")) detected.add(createStack(TechStack.DJANGO, "Framework"));
                if (content.contains("fastapi")) detected.add(createStack(TechStack.FASTAPI, "Framework"));
            }

            // 인프라 식별
            if (path.contains("dockerfile") || path.contains("docker-compose")) {
                detected.add(createStack(TechStack.DOCKER, "Infrastructure"));
            }
            if (path.contains(".github/workflows")) {
                detected.add(createStack(TechStack.GITHUB_ACTIONS, "CI/CD"));
            }
        }

        return new ArrayList<>(detected);
    }

    private DetectedStack createStack(TechStack stack, String category) {
        return DetectedStack.builder()
                .stack(stack)
                .category(category)
                .build();
    }
}
