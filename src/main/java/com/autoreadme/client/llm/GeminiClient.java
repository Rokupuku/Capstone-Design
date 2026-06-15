package com.autoreadme.client.llm;

import com.autoreadme.service.LLMClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient implements LLMClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${gemini.api.key:}")
    private String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    @Override
    public Mono<String> generateReadme(String context, String userDescription, String template, String language) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just("Gemini API Key가 설정되지 않았습니다. 기본 템플릿 결과를 반환합니다.\n\n" + context);
        }

        WebClient webClient = webClientBuilder.build();

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", buildPrompt(context, userDescription, template, language))
                ))
            )
        );

        return webClient.post()
                .uri(GEMINI_URL + "?key=" + apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractTextFromResponse)
                .retry(3) // 일시적인 503 에러 대비 3회 재시도
                .onErrorResume(e -> {
                    log.error("Gemini API call failed after retries", e);
                    return Mono.just("LLM 생성 중 오류가 발생했습니다 (재시도 실패): " + e.getMessage());
                });
    }

    private String buildPrompt(String context, String userDescription, String template, String language) {
        String langRule = switch (language == null ? "ko" : language) {
            case "en" -> "Write the entire README.md content in English only.";
            case "ja" -> "Write the entire README.md content in Japanese (日本語) only.";
            default -> "Write the entire README.md content in Korean (한국어) only.";
        };

        String structure = switch (template == null ? "standard" : template) {
            case "minimal" -> """
                    Use a concise README structure:
                    - # Project title
                    - One short overview paragraph
                    - Tech stack (bullet list)
                    - Quick start: minimal install and run commands only
                    """;
            case "detailed" -> """
                    Use a comprehensive README structure:
                    - Title, overview, features
                    - Tech stack and prerequisites
                    - Installation, configuration, usage/examples
                    - API summary if endpoints exist; data model if entities exist
                    - Optional: project tree, contributing, security reporting, license placeholder
                    """;
            default -> """
                    Use a standard professional README structure:
                    - Project name and brief description
                    - Tech stacks used
                    - Key features
                    - Getting started (prerequisites, install, run)
                    - API endpoints if available
                    - Database/entities if available
                    """;
        };

        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert software engineer. Based on the technical context below, generate a high-quality README.md.\n");
        sb.append("\n### Language requirement\n").append(langRule).append("\n");
        sb.append("\n### Template / structure requirement\n").append(structure).append("\n");

        if (userDescription != null && !userDescription.isBlank()) {
            sb.append("\n### User-provided project description\n")
                    .append(userDescription)
                    .append("\n");
        }

        sb.append("\nUse Markdown (GFM-friendly). Do not wrap the answer in a code fence unless showing commands.\n\n");
        sb.append("### Technical context\n").append(context);

        return sb.toString();
    }

    private String extractTextFromResponse(Map response) {
        try {
            List candidates = (List) response.get("candidates");
            Map candidate = (Map) candidates.get(0);
            Map content = (Map) candidate.get("content");
            List parts = (List) content.get("parts");
            Map part = (Map) parts.get(0);
            return (String) part.get("text");
        } catch (Exception e) {
            log.error("Failed to parse Gemini response", e);
            return "응답 파싱 중 오류가 발생했습니다.";
        }
    }
}
