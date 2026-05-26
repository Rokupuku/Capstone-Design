package com.autoreadme.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointInfo {
    private String method; // GET, POST, etc.
    private String url;
    private String description;
    private String controllerName;
    private String methodName;
    private String filePath;
    private String requestDto;
    private String responseDto;
}
