package com.autoreadme.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityInfo {
    private String name;
    private List<String> fields;
    private List<EntityFieldInfo> fieldDetails;
    private String tableName;
    private List<EntityRelationInfo> relationships;
    private String filePath;
}
