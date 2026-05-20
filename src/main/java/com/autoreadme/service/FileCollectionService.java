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

    // LLM 입력 컨텍스트/비용을 보호하기 위한 파일당 최대 크기(문자 기준).
    // 앞부분만 잘라 사용하며, 이후 LLM 단계에서 "완전한 파일"이 아닐 수 있음을 인지합니다.
    private static final int MAX_CHARS_PER_FILE = 30000;

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
                        .map(content -> new GitHubFileResponse(path, truncatePrefix(content)))
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

    private String truncatePrefix(String content) {
        if (content == null) return "";
        if (content.length() <= MAX_CHARS_PER_FILE) return content;

        String prefix = content.substring(0, MAX_CHARS_PER_FILE);
        // LLM이 일부만 사용되었음을 알 수 있게 짧은 마커를 뒤에 붙입니다.
        return prefix + "\n\n[TRUNCATED: using the first " + MAX_CHARS_PER_FILE + " characters only]";
    }

    private boolean isInterestingFile(String path) {
        // GitHub tree의 path는 일반적으로 '/' 구분자를 사용합니다.
        String lower = path.toLowerCase();

        // 불필요하거나 잠재적으로 너무 큰 파일은 제외 (node_modules는 보통 원격 repo에 없지만 안전장치로 둡니다)
        if (lower.contains("/node_modules/")) return false;
        if (lower.contains("/dist/")) return false;
        if (lower.contains("/build/")) return false;

        // -------------------------
        // Frontend evidence (React/Vite/JS/TS)
        // -------------------------
        // 소스 코드(프론트-백 연결 구조/호출부 분석의 근거가 됨)
        if (lower.contains("/src/") &&
                (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx"))) {
            return true;
        }

        // 프론트 빌드/툴 설정(기술 스택과 실행 방식 근거)
        if (lower.endsWith("package.json") ||
                lower.endsWith("package-lock.json") ||
                lower.endsWith("vite.config.js") ||
                lower.endsWith("vite.config.ts") ||
                lower.endsWith("index.html") ||
                lower.endsWith("tsconfig.json") ||
                lower.endsWith("eslint.config.js") ||
                lower.endsWith("tailwind.config.js") ||
                lower.endsWith("tailwind.config.ts") ||
                lower.endsWith("postcss.config.js") ||
                lower.endsWith("next.config.js")) {
            return true;
        }

        // -------------------------
        // Backend evidence (Spring/Java)
        // -------------------------
        // 자바 소스(엔드포인트/의존성/아키텍처 근거)
        if (lower.contains("/src/main/java/") && lower.endsWith(".java")) {
            return true;
        }

        // 설정/리소스(환경/실행/주요 구성 근거)
        if (lower.contains("/src/main/resources/") &&
                (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml"))) {
            return true;
        }

        // 빌드 파일/프레임워크 근거
        if (lower.endsWith("build.gradle") ||
                lower.endsWith("settings.gradle") ||
                lower.endsWith("gradle.properties") ||
                lower.endsWith("pom.xml")) {
            return true;
        }

        // 기존(이름 기반) 근거도 유지: 특정 프로젝트에서는 src/main/java 전체 대신 일부만 구성할 수 있음
        if (lower.endsWith("controller.java") ||
                lower.endsWith("entity.java") ||
                lower.endsWith("vo.java") ||
                lower.endsWith("repository.java") ||
                lower.endsWith("service.java")) {
            return true;
        }

        // -------------------------
        // Deployment / Infra evidence
        // -------------------------
        // Docker / Compose
        if (lower.endsWith("dockerfile") ||
                lower.endsWith("docker-compose.yml") ||
                lower.endsWith("docker-compose.yaml")) {
            return true;
        }

        // GitHub Actions 워크플로우
        if (lower.contains("/.github/workflows/") &&
                (lower.endsWith(".yml") || lower.endsWith(".yaml"))) {
            return true;
        }

        // -------------------------
        // License evidence (LICENSE 존재 시에만 License 섹션 생성 가능하도록 근거 확보)
        // -------------------------
        if (lower.endsWith("license") ||
                lower.endsWith("license.txt") ||
                lower.endsWith("license.md") ||
                lower.endsWith("copying") ||
                lower.endsWith("copying.txt") ||
                lower.endsWith("notice") ||
                lower.endsWith("authors")) {
            return true;
        }

        return false;
    }
}