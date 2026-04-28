package com.autoreadme.service;

import com.autoreadme.api.dto.AnalyzeGraph;
import com.autoreadme.api.dto.AnalyzeResult;
import com.autoreadme.api.dto.AnalyzeStatusResponse;
import com.autoreadme.api.dto.AnalyzeStartRequest;
import com.autoreadme.api.dto.DetectedStack;
import com.autoreadme.api.dto.EndpointInfo;
import com.autoreadme.api.dto.EntityInfo;
import com.autoreadme.api.dto.GraphEdge;
import com.autoreadme.api.dto.GraphNode;
import com.autoreadme.client.github.GitHubClient;
import com.autoreadme.client.github.model.GitHubFileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeJobService {

    public enum AnalysisStage {
        VALIDATING("입력값 검증 중", 10),
        COLLECTING("파일 수집 중", 40),
        ANALYZING("코드 분석 중", 70),
        GENERATING("문서 생성 중", 90),
        COMPLETED("분석 완료", 100),
        FAILED("분석 실패", 0);

        private final String label;
        private final int defaultProgress;

        AnalysisStage(String label, int defaultProgress) {
            this.label = label;
            this.defaultProgress = defaultProgress;
        }

        public String getLabel() { return label; }
        public int getDefaultProgress() { return defaultProgress; }
    }

    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_DONE = "done";
    private static final String STATUS_FAILED = "failed";

    private static final Duration GH_BRANCH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GH_COLLECT_TIMEOUT = Duration.ofMinutes(2);

    private final FileCollectionService fileCollectionService;
    private final GitHubClient gitHubClient;
    private final StackDetector stackDetector;
    private final CodeProfiler codeProfiler;
    private final ContextBuilder contextBuilder;

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public String start(AnalyzeStartRequest request) {
        ParsedRepo parsedRepo = parseGithubRepoUrl(request.githubUrl());
        String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        JobState state = JobState.running(jobId, AnalysisStage.VALIDATING);
        jobs.put(jobId, state);

        executor.submit(() -> runJob(jobId, parsedRepo, normalizeBranch(request.branch())));
        return jobId;
    }

    public AnalyzeStatusResponse getStatus(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            throw new NoSuchElementException("해당 jobId를 찾을 수 없습니다: " + jobId);
        }

        return new AnalyzeStatusResponse(
                state.status,
                state.jobId,
                state.stage.name(),
                state.stageProgress,
                state.error,
                state.result
        );
    }

    private void runJob(String jobId, ParsedRepo parsedRepo, String requestedBranch) {
        try {
            // 1. Validating
            updateStage(jobId, AnalysisStage.VALIDATING, 50, null);
            if (!gitHubClient.hasGitHubToken()) {
                throw new IllegalArgumentException("github.api.token 설정이 필요합니다.");
            }

            String branch = requestedBranch;
            if (branch == null) {
                branch = gitHubClient.getDefaultBranch(parsedRepo.owner(), parsedRepo.repo())
                        .block(GH_BRANCH_TIMEOUT);
            }
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }
            updateStage(jobId, AnalysisStage.VALIDATING, 100, null);

            // 2. Collecting
            updateStage(jobId, AnalysisStage.COLLECTING, 20, null);
            List<GitHubFileResponse> files = fileCollectionService
                    .collectTargetFiles(parsedRepo.owner(), parsedRepo.repo(), branch)
                    .collectList()
                    .block(GH_COLLECT_TIMEOUT);
            if (files == null) files = List.of();
            updateStage(jobId, AnalysisStage.COLLECTING, 100, null);

            // 3. Analyzing
            updateStage(jobId, AnalysisStage.ANALYZING, 30, null);
            List<DetectedStack> detectedStacks = stackDetector.detectStacks(Flux.fromIterable(files))
                    .block(Duration.ofSeconds(30));
            List<EndpointInfo> endpoints = codeProfiler.extractEndpoints(files);
            List<EntityInfo> entities = codeProfiler.extractEntities(files);
            
            AnalysisSummary summary = summarize(files, detectedStacks, endpoints, entities);
            updateStage(jobId, AnalysisStage.ANALYZING, 100, null);

            // 4. Generating (Preview)
            updateStage(jobId, AnalysisStage.GENERATING, 50, null);
            AnalyzeResult result = buildResultMarkdown(parsedRepo, branch, summary);
            updateStage(jobId, AnalysisStage.GENERATING, 100, null);

            // 5. Finalizing
            jobs.computeIfPresent(jobId, (id, prev) -> prev.done(result));
            log.info("Analyze job completed successfully. jobId={}", jobId);

        } catch (Exception e) {
            log.error("Analyze job failed. jobId={}, reason={}", jobId, e.getMessage());
            jobs.computeIfPresent(jobId, (id, prev) -> prev.failed(userFacingError(e)));
        }
    }

    private void updateStage(String jobId, AnalysisStage stage, int subProgress, AnalyzeResult result) {
        // subProgress는 해당 단계 내에서의 진행률(0~100)
        jobs.computeIfPresent(jobId, (id, prev) -> prev.runningStage(stage, subProgress, result));
    }


    private AnalysisSummary summarize(
            List<GitHubFileResponse> files, 
            List<DetectedStack> detectedStacks,
            List<EndpointInfo> endpoints,
            List<EntityInfo> entities
    ) {
        Map<String, Long> extCount = files.stream()
                .collect(Collectors.groupingBy(
                        f -> extensionOf(f.path()),
                        Collectors.counting()
                ));

        List<Map.Entry<String, Long>> topExt = extCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(8)
                .toList();

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(new GraphNode("repo", "Repository", "repo"));

        int idx = 0;
        for (Map.Entry<String, Long> ext : topExt) {
            String nodeId = "ext_" + idx++;
            String label = ext.getKey() + " (" + ext.getValue() + ")";
            nodes.add(new GraphNode(nodeId, label, "file-type"));
            edges.add(new GraphEdge("repo", nodeId, "contains"));
        }

        return new AnalysisSummary(files.size(), topExt, nodes, edges, detectedStacks, endpoints, entities);
    }

    private AnalyzeResult buildResultMarkdown(ParsedRepo repo, String branch, AnalysisSummary summary) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 기술 문서(백엔드 분석 결과)\n\n")
                .append("- 저장소: `").append(repo.owner()).append("/").append(repo.repo()).append("`\n")
                .append("- 브랜치: `").append(branch).append("`\n")
                .append("- 수집 파일 수: ").append(summary.totalFiles()).append("개\n\n");

        markdown.append("## 1. 기술 스택 (Detected Stacks)\n");
        if (summary.detectedStacks() == null || summary.detectedStacks().isEmpty()) {
            markdown.append("- 식별된 기술 스택이 없습니다.\n");
        } else {
            summary.detectedStacks().forEach(stack -> 
                markdown.append("- **").append(stack.getStack().getDisplayName()).append("** (")
                        .append(stack.getCategory()).append(")\n")
            );
        }
        markdown.append("\n");

        markdown.append("## 2. API 엔드포인트 (Endpoints)\n");
        if (summary.endpoints().isEmpty()) {
            markdown.append("- 식별된 API 엔드포인트가 없습니다.\n");
        } else {
            summary.endpoints().forEach(e -> 
                markdown.append("- `").append(e.getMethod()).append("` ").append(e.getUrl()).append("\n")
            );
        }
        markdown.append("\n");

        markdown.append("## 3. 데이터베이스 엔티티 (Entities)\n");
        if (summary.entities().isEmpty()) {
            markdown.append("- 식별된 엔티티가 없습니다.\n");
        } else {
            summary.entities().forEach(en -> 
                markdown.append("- **").append(en.getName()).append("**: ")
                        .append(String.join(", ", en.getFields())).append("\n")
            );
        }
        markdown.append("\n");

        markdown.append("## 4. 파일 유형 분포 (Top ").append(summary.topExtensions().size()).append(")\n");
        if (summary.topExtensions().isEmpty()) {
            markdown.append("- 분석 가능한 대상 파일이 없습니다.\n");
        } else {
            for (Map.Entry<String, Long> ext : summary.topExtensions()) {
                markdown.append("- ").append(ext.getKey()).append(": ").append(ext.getValue()).append("개\n");
            }
        }

        markdown.append("\n## 다음 단계\n")
                .append("- LLM 생성 문서를 도메인 섹션(아키텍처/실행방법/API)으로 확장 (9-11주차 예정)\n")
                .append("- 연결 그래프 노드/엣지 의미를 실제 코드 구조 기준으로 정교화\n");

        return new AnalyzeResult(markdown.toString(), new AnalyzeGraph(summary.nodes(), summary.edges()));
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String filename = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return "no-ext";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    private static String normalizeBranch(String branch) {
        if (branch == null || branch.isBlank()) return null;
        return branch.trim();
    }

    private static ParsedRepo parseGithubRepoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub URL이 비어 있습니다.");
        }

        try {
            URI uri = URI.create(rawUrl.trim());
            if (uri.getHost() == null || !uri.getHost().contains("github.com")) {
                throw new IllegalArgumentException("GitHub 저장소 URL 형식이 아닙니다.");
            }

            String[] segments = uri.getPath().split("/");
            List<String> cleaned = Arrays.stream(segments)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());

            if (cleaned.size() < 2) {
                throw new IllegalArgumentException("GitHub URL에서 owner/repo를 찾을 수 없습니다.");
            }

            String owner = cleaned.get(0);
            String repo = cleaned.get(1);
            if (repo.endsWith(".git")) {
                repo = repo.substring(0, repo.length() - 4);
            }

            if (owner.isBlank() || repo.isBlank()) {
                throw new IllegalArgumentException("GitHub URL에서 owner/repo를 올바르게 파싱하지 못했습니다.");
            }
            return new ParsedRepo(owner, repo);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("GitHub URL을 파싱하지 못했습니다. 예: https://github.com/{owner}/{repo}");
        }
    }

    private static String userFacingError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "분석 중 알 수 없는 오류가 발생했습니다.";
        }
        return message;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record ParsedRepo(String owner, String repo) {}

    private record AnalysisSummary(
            int totalFiles,
            List<Map.Entry<String, Long>> topExtensions,
            List<GraphNode> nodes,
            List<GraphEdge> edges,
            List<DetectedStack> detectedStacks,
            List<EndpointInfo> endpoints,
            List<EntityInfo> entities
    ) {}

    private static class JobState {
        private final String status;
        private final String jobId;
        private final AnalysisStage stage;
        private final int stageProgress;
        private final String error;
        private final AnalyzeResult result;

        private JobState(String status, String jobId, AnalysisStage stage, int stageProgress, String error, AnalyzeResult result) {
            this.status = status;
            this.jobId = jobId;
            this.stage = stage;
            this.stageProgress = stageProgress;
            this.error = error;
            this.result = result;
        }

        static JobState running(String jobId, AnalysisStage stage) {
            return new JobState(STATUS_RUNNING, jobId, stage, 0, null, null);
        }

        JobState runningStage(AnalysisStage nextStage, int subProgress, AnalyzeResult nextResult) {
            // subProgress (0~100)를 기반으로 전체 진행률 계산 (간소화)
            int totalProgress = nextStage.getDefaultProgress();
            return new JobState(STATUS_RUNNING, this.jobId, nextStage, totalProgress, null, nextResult);
        }

        JobState done(AnalyzeResult finalResult) {
            return new JobState(STATUS_DONE, this.jobId, AnalysisStage.COMPLETED, 100, null, finalResult);
        }

        JobState failed(String failureError) {
            return new JobState(STATUS_FAILED, this.jobId, AnalysisStage.FAILED, 0, failureError, null);
        }
    }
}
