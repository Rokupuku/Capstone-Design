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
    public Mono<String> generateReadme(String context, String userDescription) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just("Gemini API Key가 설정되지 않았습니다. 기본 템플릿 결과를 반환합니다.\n\n" + context);
        }

        WebClient webClient = webClientBuilder.build();

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", buildPrompt(context, userDescription))
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

    private String buildPrompt(String context, String userDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert software engineer. Generate a polished README.md in Korean from the structured project context.\n");
        sb.append("Return Markdown only. Do not include explanations outside the README.\n");
        
        if (userDescription != null && !userDescription.isBlank()) {
            sb.append("\n### User-provided Project Description:\n")
              .append(userDescription).append("\n");
        }

        sb.append("\nRequired README sections:\n")
          .append("1. 프로젝트 소개\n")
          .append("2. 주요 기능\n")
          .append("3. 기술 스택\n")
          .append("4. 실행 방법\n")
          .append("5. 환경 변수\n")
          .append("6. API 명세\n")
          .append("7. DB 구조\n")
          .append("8. 아키텍처\n")
          .append("\nRules:\n")
          .append("- Use only facts supported by the context.\n")
          .append("- If a section has no evidence, write a short note that it was not detected.\n")
          .append("- Prefer tables for API endpoints and database fields.\n\n")
          .append("### Structured Technical Context:\n")
          .append(context);
        
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
