package com.autoreadme.service;

import com.autoreadme.api.dto.EntityFieldInfo;
import com.autoreadme.api.dto.EndpointInfo;
import com.autoreadme.api.dto.EntityInfo;
import com.autoreadme.api.dto.EntityRelationInfo;
import com.autoreadme.client.github.model.GitHubFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CodeProfiler {

    private static final Pattern CLASS_NAME = Pattern.compile("(?:public\\s+)?(?:class|record)\\s+(\\w+)");
    private static final Pattern CLASS_REQUEST_MAPPING = Pattern.compile("@RequestMapping\\s*(?:\\(([^)]*)\\))?[\\s\\S]*?(?:public\\s+)?class\\s+\\w+");
    private static final Pattern MAPPING_METHOD = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\\s*(?:\\(([^)]*)\\))?\\s+" +
                    "(?:public|private|protected)?\\s*([\\w<>.? ,\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.MULTILINE
    );

    private static final Pattern TABLE_NAME = Pattern.compile("@Table\\s*\\([^)]*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern FIELD = Pattern.compile("((?:\\s*@\\w+(?:\\([^)]*\\))?\\s*)*)\\s*private\\s+([\\w<>.]+)\\s+(\\w+)\\s*;");
    private static final Pattern COLUMN_NAME = Pattern.compile("@Column\\s*\\([^)]*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern RELATION = Pattern.compile("@(ManyToOne|OneToMany|OneToOne|ManyToMany)");
    private static final Pattern REQUEST_BODY = Pattern.compile("@RequestBody\\s+([\\w<>.]+)");

    public List<EndpointInfo> extractEndpoints(List<GitHubFileResponse> files) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        for (GitHubFileResponse file : files) {
            String content = file.content();
            if (file.path().endsWith(".java") && (content.contains("@RestController") || content.contains("@Controller"))) {
                endpoints.addAll(parseEndpoints(file.path(), content));
            }
        }
        return endpoints;
    }

    public List<EntityInfo> extractEntities(List<GitHubFileResponse> files) {
        List<EntityInfo> entities = new ArrayList<>();
        for (GitHubFileResponse file : files) {
            String content = file.content();
            if (file.path().endsWith(".java") && content.contains("@Entity")) {
                entities.add(parseEntity(file.path(), content));
            }
        }
        return entities;
    }

    private List<EndpointInfo> parseEndpoints(String filePath, String content) {
        String controllerName = findFirst(CLASS_NAME, content, "UnknownController");
        String prefix = "/";
        Matcher classMatcher = CLASS_REQUEST_MAPPING.matcher(content);
        if (classMatcher.find()) {
            prefix = firstPathValue(classMatcher.group(1));
        }

        List<EndpointInfo> list = new ArrayList<>();
        Matcher methodMatcher = MAPPING_METHOD.matcher(content);
        while (methodMatcher.find()) {
            String annotation = methodMatcher.group(1);
            String args = methodMatcher.group(2);
            String returnType = methodMatcher.group(3).trim();
            String methodName = methodMatcher.group(4);
            String parameters = methodMatcher.group(5);

            list.add(EndpointInfo.builder()
                    .method(httpMethod(annotation, args))
                    .url(combinePath(prefix, firstPathValue(args)))
                    .controllerName(controllerName)
                    .methodName(methodName)
                    .filePath(filePath)
                    .requestDto(extractRequestDto(parameters))
                    .responseDto(normalizeResponseDto(returnType))
                    .build());
        }
        return list;
    }

    private String httpMethod(String annotation, String args) {
        return switch (annotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            default -> requestMappingMethod(args);
        };
    }

    private String requestMappingMethod(String args) {
        if (args == null) return "ALL";
        String upper = args.toUpperCase(Locale.ROOT);
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            if (upper.contains("REQUESTMETHOD." + method) || upper.contains("METHOD = " + method)) {
                return method;
            }
        }
        return "ALL";
    }

    private String firstPathValue(String args) {
        if (args == null || args.isBlank()) return "/";
        Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(args);
        return matcher.find() ? matcher.group(1) : "/";
    }

    private String combinePath(String prefix, String suffix) {
        String p = (prefix == null || prefix.isBlank()) ? "" : prefix.trim();
        String s = (suffix == null || suffix.isBlank()) ? "" : suffix.trim();
        if ("/".equals(p)) p = "";
        if ("/".equals(s)) s = "";
        if (!p.isBlank() && !p.startsWith("/")) p = "/" + p;
        if (!s.isBlank() && !s.startsWith("/")) s = "/" + s;
        String combined = p + s;
        return combined.isBlank() ? "/" : combined;
    }

    private String extractRequestDto(String parameters) {
        if (parameters == null || parameters.isBlank()) return null;
        Matcher matcher = REQUEST_BODY.matcher(parameters);
        return matcher.find() ? simpleTypeName(matcher.group(1)) : null;
    }

    private String normalizeResponseDto(String returnType) {
        if (returnType == null || returnType.isBlank() || "void".equals(returnType)) return null;
        String normalized = returnType.replaceAll("\\s+", "");
        Matcher responseEntity = Pattern.compile("ResponseEntity<([^>]+)>").matcher(normalized);
        if (responseEntity.find()) {
            return simpleTypeName(responseEntity.group(1));
        }
        return simpleTypeName(normalized);
    }

    private EntityInfo parseEntity(String filePath, String content) {
        String name = findFirst(CLASS_NAME, content, "Unknown");
        String tableName = findFirst(TABLE_NAME, content, null);
        List<String> fields = new ArrayList<>();
        List<EntityFieldInfo> fieldDetails = new ArrayList<>();
        List<EntityRelationInfo> relationships = new ArrayList<>();

        Matcher fieldMatcher = FIELD.matcher(content);
        while (fieldMatcher.find()) {
            String annotations = fieldMatcher.group(1);
            String type = simpleTypeName(fieldMatcher.group(2));
            String fieldName = fieldMatcher.group(3);
            fields.add(fieldName);
            fieldDetails.add(EntityFieldInfo.builder()
                    .name(fieldName)
                    .type(type)
                    .columnName(findFirst(COLUMN_NAME, annotations, null))
                    .build());

            Matcher relationMatcher = RELATION.matcher(annotations);
            if (relationMatcher.find()) {
                relationships.add(EntityRelationInfo.builder()
                        .fieldName(fieldName)
                        .relationType(relationMatcher.group(1))
                        .targetEntity(type)
                        .build());
            }
        }

        return EntityInfo.builder()
                .name(name)
                .fields(fields)
                .fieldDetails(fieldDetails)
                .tableName(tableName)
                .relationships(relationships)
                .filePath(filePath)
                .build();
    }

    private String findFirst(Pattern pattern, String content, String fallback) {
        if (content == null) return fallback;
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String simpleTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) return null;
        String trimmed = rawType.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }
}
