package com.autoreadme.service;

import com.autoreadme.api.dto.DetectedStack;
import com.autoreadme.api.dto.EndpointInfo;
import com.autoreadme.api.dto.EntityInfo;
import com.autoreadme.client.github.model.GitHubFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContextBuilder {

    public String buildContext(
            List<DetectedStack> stacks,
            List<EndpointInfo> endpoints,
            List<EntityInfo> entities,
            List<GitHubFileResponse> coreFiles
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Project Technical Context\n\n");

        // 1. Tech Stacks
        sb.append("#### 1. Detected Tech Stacks\n");
        if (stacks.isEmpty()) {
            sb.append("- No specific tech stacks detected.\n");
        } else {
            stacks.forEach(s -> sb.append("- ").append(s.getStack().getDisplayName())
                    .append(" (").append(s.getCategory()).append(")\n"));
        }
        sb.append("\n");

        // 2. API Endpoints
        sb.append("#### 2. API Endpoints\n");
        if (endpoints.isEmpty()) {
            sb.append("- No endpoints detected.\n");
        } else {
            endpoints.forEach(e -> sb.append("- [").append(e.getMethod()).append("] ")
                    .append(e.getUrl()).append("\n"));
        }
        sb.append("\n");

        // 3. Database Entities
        sb.append("#### 3. Database Entities\n");
        if (entities.isEmpty()) {
            sb.append("- No entities detected.\n");
        } else {
            entities.forEach(en -> {
                sb.append("- ").append(en.getName()).append(": ")
                        .append(String.join(", ", en.getFields())).append("\n");
            });
        }
        sb.append("\n");

        // 4. Core Files Content
        sb.append("#### 4. Core Files Content\n");
        for (GitHubFileResponse file : coreFiles) {
            sb.append("--- File: ").append(file.path()).append(" ---\n");
            sb.append(file.content()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * LLM에 보낼 핵심 파일을 선별합니다.
     */
    public List<GitHubFileResponse> selectCoreFiles(List<GitHubFileResponse> allFiles) {
        return allFiles.stream()
                .filter(f -> isCoreFile(f.path()))
                .limit(5) // 토큰 보호를 위해 최대 5개로 제한
                .collect(Collectors.toList());
    }

    private boolean isCoreFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("application.java") ||
               lower.endsWith("application.properties") ||
               lower.endsWith("application.yml") ||
               lower.endsWith("build.gradle") ||
               lower.endsWith("package.json");
    }
}
