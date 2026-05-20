package com.autoreadme.api;

import com.autoreadme.api.dto.AnalyzeStartRequest;
import com.autoreadme.api.dto.AnalyzeStartResponse;
import com.autoreadme.api.dto.AnalyzeStatusResponse;
import com.autoreadme.service.AnalyzeJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analyze")
@CrossOrigin(origins = "http://localhost:5173")
public class AnalyzeController {

    private final AnalyzeJobService analyzeJobService;

    @PostMapping
    public ResponseEntity<AnalyzeStartResponse> start(@RequestBody AnalyzeStartRequest request) {
        String jobId = analyzeJobService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AnalyzeStartResponse(jobId, "running"));
    }

    @GetMapping("/{jobId}")
    public AnalyzeStatusResponse status(@PathVariable String jobId) {
        return analyzeJobService.getStatus(jobId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", e.getMessage()
        ));
    }
}
