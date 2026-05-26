package com.autoreadme.domain;

import com.autoreadme.service.AnalyzeJobService.AnalysisStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analyze_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyzeJobEntity {
    @Id
    private String jobId;

    private String status;
    
    @Enumerated(EnumType.STRING)
    private AnalysisStage stage;
    
    private int stageProgress;

    private String errorCode;

    private String owner;

    private String repo;

    private String branch;

    private Integer collectedFileCount;

    private Integer detectedStackCount;
    
    @Column(columnDefinition = "TEXT")
    private String markdown;
    
    @Column(columnDefinition = "TEXT")
    private String graphJson;
    
    private String error;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
