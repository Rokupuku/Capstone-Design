package com.autoreadme.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityRelationInfo {
    private String fieldName;
    private String relationType;
    private String targetEntity;
}
