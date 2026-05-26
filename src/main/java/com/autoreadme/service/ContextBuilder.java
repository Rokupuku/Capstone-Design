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
        sb.append("<project_context>\n");

        sb.append("  <readme_template>\n");
        sb.append("    <section>프로젝트 소개</section>\n");
        sb.append("    <section>주요 기능</section>\n");
        sb.append("    <section>기술 스택</section>\n");
        sb.append("    <section>실행 방법</section>\n");
        sb.append("    <section>환경 변수</section>\n");
        sb.append("    <section>API 명세</section>\n");
        sb.append("    <section>DB 구조</section>\n");
        sb.append("    <section>아키텍처</section>\n");
        sb.append("  </readme_template>\n");

        sb.append("  <detected_stacks>\n");
        for (DetectedStack stack : stacks) {
            sb.append("    <stack category=\"").append(xml(stack.getCategory())).append("\">")
                    .append(xml(stack.getStack().getDisplayName()))
                    .append("</stack>\n");
        }
        sb.append("  </detected_stacks>\n");

        sb.append("  <api_endpoints>\n");
        for (EndpointInfo endpoint : endpoints) {
            sb.append("    <endpoint method=\"").append(xml(endpoint.getMethod()))
                    .append("\" path=\"").append(xml(endpoint.getUrl()))
                    .append("\" controller=\"").append(xml(endpoint.getControllerName()))
                    .append("\" handler=\"").append(xml(endpoint.getMethodName()))
                    .append("\" file=\"").append(xml(endpoint.getFilePath()))
                    .append("\" request=\"").append(xml(endpoint.getRequestDto()))
                    .append("\" response=\"").append(xml(endpoint.getResponseDto()))
                    .append("\" />\n");
        }
        sb.append("  </api_endpoints>\n");

        sb.append("  <database_entities>\n");
        for (EntityInfo entity : entities) {
            sb.append("    <entity name=\"").append(xml(entity.getName()))
                    .append("\" table=\"").append(xml(entity.getTableName()))
                    .append("\" file=\"").append(xml(entity.getFilePath()))
                    .append("\">\n");
            if (entity.getFieldDetails() != null) {
                for (var field : entity.getFieldDetails()) {
                    sb.append("      <field name=\"").append(xml(field.getName()))
                            .append("\" type=\"").append(xml(field.getType()))
                            .append("\" column=\"").append(xml(field.getColumnName()))
                            .append("\" />\n");
                }
            }
            if (entity.getRelationships() != null) {
                for (var relation : entity.getRelationships()) {
                    sb.append("      <relation field=\"").append(xml(relation.getFieldName()))
                            .append("\" type=\"").append(xml(relation.getRelationType()))
                            .append("\" target=\"").append(xml(relation.getTargetEntity()))
                            .append("\" />\n");
                }
            }
            sb.append("    </entity>\n");
        }
        sb.append("  </database_entities>\n");

        sb.append("  <core_files>\n");
        for (GitHubFileResponse file : coreFiles) {
            sb.append("    <file path=\"").append(xml(file.path())).append("\"><![CDATA[\n");
            sb.append(stripComments(file.content())).append("\n");
            sb.append("    ]]></file>\n");
        }
        sb.append("  </core_files>\n");
        sb.append("</project_context>\n");

        return sb.toString();
    }

    private String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String stripComments(String content) {
        if (content == null) return "";
        // Simple regex to remove single-line (//) and multi-line (/* */) comments
        // Note: This is a basic implementation and might not handle all edge cases (e.g. comments inside strings)
        return content.replaceAll("//.*|/\\*(?:.|[\\n\\r])*?\\*/", "")
                .replaceAll("(?m)^[ \t]*\r?\n", ""); // Remove empty lines
    }

    /**
     * LLM에 보낼 핵심 파일을 선별합니다.
     */
    public List<GitHubFileResponse> selectCoreFiles(List<GitHubFileResponse> allFiles) {
        return allFiles.stream()
                .filter(f -> isCoreFile(f.path()))
                .limit(10) // 토큰 보호를 위해 최대 10개로 제한
                .collect(Collectors.toList());
    }

    private boolean isCoreFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("application.java") ||
               lower.endsWith("application.properties") ||
               lower.endsWith("application.yml") ||
               lower.endsWith("build.gradle") ||
               lower.endsWith("package.json") ||
               lower.endsWith("service.java") ||
               lower.endsWith("controller.java") ||
               lower.endsWith("repository.java");
    }
}
