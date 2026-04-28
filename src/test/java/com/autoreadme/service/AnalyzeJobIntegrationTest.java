package com.autoreadme.service;

import com.autoreadme.api.dto.AnalyzeStartRequest;
import com.autoreadme.api.dto.AnalyzeStatusResponse;
import com.autoreadme.client.github.GitHubClient;
import com.autoreadme.client.github.model.GitHubFileResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest
class AnalyzeJobIntegrationTest {

    @Autowired
    private AnalyzeJobService analyzeJobService;

    @MockitoBean
    private GitHubClient gitHubClient;

    @MockitoBean
    private FileCollectionService fileCollectionService;

    @Test
    @DisplayName("전체 분석 파이프라인 통합 테스트 (Mock)")
    void fullPipelineTest() {
        // Given
        String githubUrl = "https://github.com/test/repo";
        AnalyzeStartRequest request = new AnalyzeStartRequest(githubUrl, "main");

        Mockito.when(gitHubClient.hasGitHubToken()).thenReturn(true);
        Mockito.when(gitHubClient.getDefaultBranch(anyString(), anyString())).thenReturn(Mono.just("main"));
        
        List<GitHubFileResponse> mockFiles = List.of(
            new GitHubFileResponse("build.gradle", "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }"),
            new GitHubFileResponse("src/main/java/com/test/TestController.java", "@RestController\npublic class TestController { @GetMapping(\"/api/hello\") public String hello() { return \"hi\"; } }")
        );
        Mockito.when(fileCollectionService.collectTargetFiles(anyString(), anyString(), anyString()))
                .thenReturn(Flux.fromIterable(mockFiles));

        // When
        String jobId = analyzeJobService.start(request);

        // Then
        assertThat(jobId).startsWith("job_");

        // 비동기 실행이므로 완료될 때까지 대기 (최대 10초)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            AnalyzeStatusResponse status = analyzeJobService.getStatus(jobId);
            assertThat(status.status()).isEqualTo("done");
            assertThat(status.stage()).isEqualTo("COMPLETED");
            assertThat(status.result().markdown()).contains("Spring Boot", "/api/hello");
        });
    }
}
