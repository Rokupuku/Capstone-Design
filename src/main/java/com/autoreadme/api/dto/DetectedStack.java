package com.autoreadme.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectedStack {
    private TechStack stack;
    private String version;
    private String category; // e.g., "Language", "Framework", "Database"
}
