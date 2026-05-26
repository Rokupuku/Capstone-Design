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
import com.autoreadme.domain.AnalyzeJobEntity;
import com.autoreadme.domain.AnalyzeJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
        VALIDATING("입력값 검증 중", 0, 10),
        COLLECTING_FILES("파일 수집 중", 10, 35),
        DETECTING_STACK("기술 스택 분석 중", 35, 50),
        PROFILING_CODE("코드 구조 분석 중", 50, 65),
        BUILDING_CONTEXT("LLM 컨텍스트 구성 중", 65, 78),
        GENERATING_README("README 생성 중", 78, 95),
        COMPLETED("분석 완료", 100, 100),
        FAILED("분석 실패", 0, 0);

        private final String label;
        private final int startProgress;
        private final int endProgress;

        AnalysisStage(String label, int startProgress, int endProgress) {
            this.label = label;
            this.startProgress = startProgress;
            this.endProgress = endProgress;
        }

        public String getLabel() { return label; }
        public int progressAt(int subProgress) {
            int clamped = Math.max(0, Math.min(100, subProgress));
            return startProgress + ((endProgress - startProgress) * clamped / 100);
        }
    }

    public enum FailureCode {
        INVALID_GITHUB_URL,
        GITHUB_TOKEN_MISSING,
        REPOSITORY_NOT_FOUND,
        RATE_LIMITED,
        LLM_GENERATION_FAILED,
        UNKNOWN_ERROR
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
    private final LLMClient llmClient;
    private final AnalyzeJobRepository analyzeJobRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public String start(AnalyzeStartRequest request) {
        ParsedRepo parsedRepo = parseGithubRepoUrl(request.githubUrl());
        String branch = normalizeBranch(request.branch());
        String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        JobState state = JobState.running(jobId, AnalysisStage.VALIDATING, parsedRepo, branch);
        jobs.put(jobId, state);
        saveToDb(state);

        executor.submit(() -> runJob(jobId, parsedRepo, branch, request.projectDescription()));
        return jobId;
    }

    public AnalyzeStatusResponse getStatus(String jobId) {
        JobState state = jobs.get(jobId);
        if (state != null) {
            return new AnalyzeStatusResponse(
                    state.status,
                    state.jobId,
                    state.stage.name(),
                    state.stageProgress,
                    state.errorCode,
                    state.error,
                    state.result
            );
        }

        return analyzeJobRepository.findById(jobId)
                .map(this::mapToStatusResponse)
                .orElseThrow(() -> new NoSuchElementException("해당 jobId를 찾을 수 없습니다: " + jobId));
    }

    private AnalyzeStatusResponse mapToStatusResponse(AnalyzeJobEntity entity) {
        AnalyzeGraph graph = null;
        if (entity.getGraphJson() != null) {
            try {
                graph = objectMapper.readValue(entity.getGraphJson(), AnalyzeGraph.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize graph for jobId: {}", entity.getJobId());
            }
        }

        AnalyzeResult result = (entity.getMarkdown() != null || graph != null)
                ? new AnalyzeResult(entity.getMarkdown(), graph)
                : null;

        return new AnalyzeStatusResponse(
                entity.getStatus(),
                entity.getJobId(),
                entity.getStage().name(),
                entity.getStageProgress(),
                entity.getErrorCode(),
                entity.getError(),
                result
        );
    }

    private void runJob(String jobId, ParsedRepo parsedRepo, String requestedBranch, String userDescription) {
        try {
            updateStage(jobId, AnalysisStage.VALIDATING, 50, null);
            if (!gitHubClient.hasGitHubToken()) {
                throw new AnalyzeJobException(FailureCode.GITHUB_TOKEN_MISSING, "github.api.token 설정이 필요합니다.");
            }

            String branch = requestedBranch;
            if (branch == null) {
                branch = gitHubClient.getDefaultBranch(parsedRepo.owner(), parsedRepo.repo())
                        .block(GH_BRANCH_TIMEOUT);
            }
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }
            updateMetadata(jobId, branch, null, null);
            updateStage(jobId, AnalysisStage.VALIDATING, 100, null);

            updateStage(jobId, AnalysisStage.COLLECTING_FILES, 10, null);
            List<GitHubFileResponse> files = fileCollectionService
                    .collectTargetFiles(parsedRepo.owner(), parsedRepo.repo(), branch)
                    .collectList()
                    .block(GH_COLLECT_TIMEOUT);
            if (files == null) files = List.of();
            updateMetadata(jobId, branch, files.size(), null);
            updateStage(jobId, AnalysisStage.COLLECTING_FILES, 100, null);

            updateStage(jobId, AnalysisStage.DETECTING_STACK, 20, null);
            List<DetectedStack> detectedStacks = stackDetector.detectStacks(Flux.fromIterable(files))
                    .block(Duration.ofSeconds(30));
            if (detectedStacks == null) detectedStacks = List.of();
            updateMetadata(jobId, branch, files.size(), detectedStacks.size());
            updateStage(jobId, AnalysisStage.DETECTING_STACK, 100, null);

            updateStage(jobId, AnalysisStage.PROFILING_CODE, 20, null);
            List<EndpointInfo> endpoints = codeProfiler.extractEndpoints(files);
            List<EntityInfo> entities = codeProfiler.extractEntities(files);
            
            AnalysisSummary summary = summarize(files, detectedStacks, endpoints, entities);
            updateStage(jobId, AnalysisStage.PROFILING_CODE, 100, null);

            updateStage(jobId, AnalysisStage.BUILDING_CONTEXT, 20, null);
            List<GitHubFileResponse> coreFiles = contextBuilder.selectCoreFiles(files);
            String context = contextBuilder.buildContext(detectedStacks, endpoints, entities, coreFiles);
            updateStage(jobId, AnalysisStage.BUILDING_CONTEXT, 100, null);
            
            updateStage(jobId, AnalysisStage.GENERATING_README, 20, null);
            String fallbackMarkdown = buildResultMarkdown(parsedRepo, branch, summary).markdown();
            String aiMarkdown = generateReadmeWithFallback(context, userDescription, fallbackMarkdown);
            
            AnalyzeResult result = new AnalyzeResult(aiMarkdown, new AnalyzeGraph(summary.nodes(), summary.edges()));
            updateStage(jobId, AnalysisStage.GENERATING_README, 100, result);

            jobs.computeIfPresent(jobId, (id, prev) -> {
                JobState next = prev.done(result);
                saveToDb(next);
                return next;
            });
            log.info("Analyze job completed successfully. jobId={}", jobId);

        } catch (Exception e) {
            log.error("Analyze job failed. jobId={}, reason={}", jobId, e.getMessage());
            jobs.computeIfPresent(jobId, (id, prev) -> {
                JobState next = prev.failed(failureCode(e), userFacingError(e));
                saveToDb(next);
                return next;
            });
        }
    }

    private String generateReadmeWithFallback(String context, String userDescription, String fallbackMarkdown) {
        try {
            String generated = llmClient.generateReadme(context, userDescription)
                    .block(Duration.ofMinutes(1));
            if (generated == null ||
                    generated.isBlank() ||
                    generated.contains("LLM 생성 중 오류가 발생했습니다") ||
                    generated.contains("Gemini API Key가 설정되지 않았습니다")) {
                return fallbackMarkdown;
            }
            return generated;
        } catch (Exception e) {
            log.warn("LLM README generation failed. fallback README will be used. reason={}", e.getMessage());
            return fallbackMarkdown;
        }
    }

    private void updateStage(String jobId, AnalysisStage stage, int subProgress, AnalyzeResult result) {
        jobs.computeIfPresent(jobId, (id, prev) -> {
            JobState next = prev.runningStage(stage, subProgress, result);
            saveToDb(next);
            return next;
        });
    }

    private void updateMetadata(String jobId, String branch, Integer collectedFileCount, Integer detectedStackCount) {
        jobs.computeIfPresent(jobId, (id, prev) -> {
            JobState next = prev.withMetadata(branch, collectedFileCount, detectedStackCount);
            saveToDb(next);
            return next;
        });
    }

    private void saveToDb(JobState state) {
        String graphJson = null;
        if (state.result != null && state.result.graph() != null) {
            try {
                graphJson = objectMapper.writeValueAsString(state.result.graph());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize graph for jobId: {}", state.jobId);
            }
        }

        AnalyzeJobEntity entity = AnalyzeJobEntity.builder()
                .jobId(state.jobId)
                .status(state.status)
                .stage(state.stage)
                .stageProgress(state.stageProgress)
                .errorCode(state.errorCode)
                .owner(state.owner)
                .repo(state.repo)
                .branch(state.branch)
                .collectedFileCount(state.collectedFileCount)
                .detectedStackCount(state.detectedStackCount)
                .markdown(state.result != null ? state.result.markdown() : null)
                .graphJson(graphJson)
                .error(state.error)
                .build();

        analyzeJobRepository.findById(state.jobId)
                .map(AnalyzeJobEntity::getCreatedAt)
                .ifPresent(entity::setCreatedAt);
        analyzeJobRepository.save(entity);
    }


    private AnalysisSummary summarize(
            List<GitHubFileResponse> files, 
            List<DetectedStack> detectedStacks,
            List<EndpointInfo> endpoints,
            List<EntityInfo> entities
    ) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        
        // 1. Central Repository Node
        String repoId = "repo_root";
        nodes.add(new GraphNode(repoId, "Project Repository", "repo"));

        // 2. Tech Stacks
        if (detectedStacks != null) {
            for (int i = 0; i < detectedStacks.size(); i++) {
                DetectedStack ds = detectedStacks.get(i);
                String stackId = "stack_" + i;
                nodes.add(new GraphNode(stackId, ds.getStack().getDisplayName(), "stack"));
                edges.add(new GraphEdge(repoId, stackId, "uses"));
            }
        }

        // 3. API Endpoints (Top 8 for clarity)
        int endpointLimit = Math.min(endpoints.size(), 8);
        for (int i = 0; i < endpointLimit; i++) {
            EndpointInfo ep = endpoints.get(i);
            String epId = "ep_" + i;
            String label = ep.getMethod() + " " + ep.getUrl();
            nodes.add(new GraphNode(epId, label, "endpoint"));
            edges.add(new GraphEdge(repoId, epId, "exposes"));
        }

        // 4. Database Entities (Top 8 for clarity)
        int entityLimit = Math.min(entities.size(), 8);
        for (int i = 0; i < entityLimit; i++) {
            EntityInfo en = entities.get(i);
            String enId = "en_" + i;
            nodes.add(new GraphNode(enId, en.getName(), "entity"));
            edges.add(new GraphEdge(repoId, enId, "persists"));
        }

        // 5. File Extensions (Simplified)
        Map<String, Long> extCount = files.stream()
                .collect(Collectors.groupingBy(
                        f -> extensionOf(f.path()),
                        Collectors.counting()
                ));

        List<Map.Entry<String, Long>> topExt = extCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .toList();

        return new AnalysisSummary(files.size(), topExt, nodes, edges, detectedStacks, endpoints, entities);
    }

    private AnalyzeResult buildResultMarkdown(ParsedRepo repo, String branch, AnalysisSummary summary) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(repo.repo()).append("\n\n")
                .append("> LLM 생성 실패 시 정적 분석 결과만으로 생성된 fallback README입니다.\n\n");

        markdown.append("## 프로젝트 소개\n\n")
                .append("- 저장소: `").append(repo.owner()).append("/").append(repo.repo()).append("`\n")
                .append("- 브랜치: `").append(branch).append("`\n")
                .append("- 분석 파일 수: ").append(summary.totalFiles()).append("개\n\n");

        markdown.append("## 주요 기능\n\n")
                .append("- GitHub 저장소 파일 구조를 기반으로 기술 스택, API 엔드포인트, DB 엔티티를 분석합니다.\n")
                .append("- 분석 결과를 README 작성에 활용할 수 있는 구조화된 형태로 정리합니다.\n\n");

        markdown.append("## 기술 스택\n\n");
        if (summary.detectedStacks() == null || summary.detectedStacks().isEmpty()) {
            markdown.append("- 식별된 기술 스택이 없습니다.\n");
        } else {
            summary.detectedStacks().forEach(stack -> 
                markdown.append("- **").append(stack.getStack().getDisplayName()).append("** (")
                        .append(stack.getCategory()).append(")\n")
            );
        }
        markdown.append("\n");

        markdown.append("## 실행 방법\n\n")
                .append("- 실행 방법은 프로젝트 설정 파일을 기반으로 수동 확인이 필요합니다.\n\n");

        markdown.append("## 환경 변수\n\n")
                .append("- 자동 분석에서 확정 가능한 환경 변수 정보가 감지되지 않았습니다.\n\n");

        markdown.append("## API 명세\n\n");
        if (summary.endpoints().isEmpty()) {
            markdown.append("- 식별된 API 엔드포인트가 없습니다.\n");
        } else {
            markdown.append("| Method | URL | Controller | Handler | Request | Response |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n");
            summary.endpoints().forEach(e -> 
                markdown.append("| `").append(e.getMethod()).append("` | `").append(e.getUrl()).append("` | ")
                        .append(nullToDash(e.getControllerName())).append(" | ")
                        .append(nullToDash(e.getMethodName())).append(" | ")
                        .append(nullToDash(e.getRequestDto())).append(" | ")
                        .append(nullToDash(e.getResponseDto())).append(" |\n")
            );
        }
        markdown.append("\n");

        markdown.append("## DB 구조\n\n");
        if (summary.entities().isEmpty()) {
            markdown.append("- 식별된 엔티티가 없습니다.\n");
        } else {
            summary.entities().forEach(en -> {
                markdown.append("### ").append(en.getName()).append("\n\n");
                if (en.getTableName() != null && !en.getTableName().isBlank()) {
                    markdown.append("- Table: `").append(en.getTableName()).append("`\n\n");
                }
                markdown.append("| Field | Type | Column |\n")
                        .append("| --- | --- | --- |\n");
                if (en.getFieldDetails() == null || en.getFieldDetails().isEmpty()) {
                    markdown.append("| - | - | - |\n");
                } else {
                    en.getFieldDetails().forEach(field ->
                            markdown.append("| ").append(field.getName()).append(" | ")
                                    .append(nullToDash(field.getType())).append(" | ")
                                    .append(nullToDash(field.getColumnName())).append(" |\n")
                    );
                }
                markdown.append("\n");
            });
        }

        markdown.append("## 아키텍처\n\n")
                .append("- 정적 분석 그래프는 기술 스택, API 엔드포인트, 엔티티를 저장소 루트와 연결해 구성됩니다.\n\n");

        markdown.append("## 파일 유형 분포\n\n");
        if (summary.topExtensions().isEmpty()) {
            markdown.append("- 분석 가능한 대상 파일이 없습니다.\n");
        } else {
            for (Map.Entry<String, Long> ext : summary.topExtensions()) {
                markdown.append("- ").append(ext.getKey()).append(": ").append(ext.getValue()).append("개\n");
            }
        }

        return new AnalyzeResult(markdown.toString(), new AnalyzeGraph(summary.nodes(), summary.edges()));
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
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
        if (e instanceof AnalyzeJobException analyzeJobException) {
            return analyzeJobException.getMessage();
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "분석 중 알 수 없는 오류가 발생했습니다.";
        }
        return message;
    }

    private static String failureCode(Exception e) {
        if (e instanceof AnalyzeJobException analyzeJobException) {
            return analyzeJobException.code().name();
        }
        if (e instanceof WebClientResponseException.NotFound) {
            return FailureCode.REPOSITORY_NOT_FOUND.name();
        }
        if (e instanceof WebClientResponseException.TooManyRequests) {
            return FailureCode.RATE_LIMITED.name();
        }
        if (e instanceof IllegalArgumentException) {
            return FailureCode.INVALID_GITHUB_URL.name();
        }
        return FailureCode.UNKNOWN_ERROR.name();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record ParsedRepo(String owner, String repo) {}

    private static class AnalyzeJobException extends RuntimeException {
        private final FailureCode code;

        AnalyzeJobException(FailureCode code, String message) {
            super(message);
            this.code = code;
        }

        FailureCode code() {
            return code;
        }
    }

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
        private final String errorCode;
        private final String error;
        private final AnalyzeResult result;
        private final String owner;
        private final String repo;
        private final String branch;
        private final Integer collectedFileCount;
        private final Integer detectedStackCount;

        private JobState(
                String status,
                String jobId,
                AnalysisStage stage,
                int stageProgress,
                String errorCode,
                String error,
                AnalyzeResult result,
                String owner,
                String repo,
                String branch,
                Integer collectedFileCount,
                Integer detectedStackCount
        ) {
            this.status = status;
            this.jobId = jobId;
            this.stage = stage;
            this.stageProgress = stageProgress;
            this.errorCode = errorCode;
            this.error = error;
            this.result = result;
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
            this.collectedFileCount = collectedFileCount;
            this.detectedStackCount = detectedStackCount;
        }

        static JobState running(String jobId, AnalysisStage stage, ParsedRepo parsedRepo, String branch) {
            return new JobState(
                    STATUS_RUNNING,
                    jobId,
                    stage,
                    stage.progressAt(0),
                    null,
                    null,
                    null,
                    parsedRepo.owner(),
                    parsedRepo.repo(),
                    branch,
                    null,
                    null
            );
        }

        JobState runningStage(AnalysisStage nextStage, int subProgress, AnalyzeResult nextResult) {
            return new JobState(
                    STATUS_RUNNING,
                    this.jobId,
                    nextStage,
                    nextStage.progressAt(subProgress),
                    null,
                    null,
                    nextResult,
                    this.owner,
                    this.repo,
                    this.branch,
                    this.collectedFileCount,
                    this.detectedStackCount
            );
        }

        JobState withMetadata(String nextBranch, Integer nextCollectedFileCount, Integer nextDetectedStackCount) {
            return new JobState(
                    this.status,
                    this.jobId,
                    this.stage,
                    this.stageProgress,
                    this.errorCode,
                    this.error,
                    this.result,
                    this.owner,
                    this.repo,
                    nextBranch != null ? nextBranch : this.branch,
                    nextCollectedFileCount != null ? nextCollectedFileCount : this.collectedFileCount,
                    nextDetectedStackCount != null ? nextDetectedStackCount : this.detectedStackCount
            );
        }

        JobState done(AnalyzeResult finalResult) {
            return new JobState(
                    STATUS_DONE,
                    this.jobId,
                    AnalysisStage.COMPLETED,
                    100,
                    null,
                    null,
                    finalResult,
                    this.owner,
                    this.repo,
                    this.branch,
                    this.collectedFileCount,
                    this.detectedStackCount
            );
        }

        JobState failed(String failureCode, String failureError) {
            return new JobState(
                    STATUS_FAILED,
                    this.jobId,
                    AnalysisStage.FAILED,
                    0,
                    failureCode,
                    failureError,
                    null,
                    this.owner,
                    this.repo,
                    this.branch,
                    this.collectedFileCount,
                    this.detectedStackCount
            );
        }
    }
}
