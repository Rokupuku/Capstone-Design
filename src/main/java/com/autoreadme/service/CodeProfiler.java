package com.autoreadme.service;

import com.autoreadme.api.dto.EndpointInfo;
import com.autoreadme.api.dto.EntityInfo;
import com.autoreadme.client.github.model.GitHubFileResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CodeProfiler {

    // 정규표현식 패턴 개선: @GetMapping("/url"), @PostMapping(value = "/url") 등 대응
    private static final Pattern GET_MAPPING = Pattern.compile("@GetMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"(.*?)\"\\s*\\)");
    private static final Pattern POST_MAPPING = Pattern.compile("@PostMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"(.*?)\"\\s*\\)");
    private static final Pattern REQUEST_MAPPING = Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"(.*?)\"\\s*\\)");
    
    private static final Pattern ENTITY_NAME = Pattern.compile("public\\s+class\\s+(\\w+)");
    private static final Pattern FIELD_NAME = Pattern.compile("private\\s+\\w+\\s+(\\w+);");

    public List<EndpointInfo> extractEndpoints(List<GitHubFileResponse> files) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        for (GitHubFileResponse file : files) {
            String content = file.content();
            if (file.path().endsWith(".java") && (content.contains("@RestController") || content.contains("@Controller"))) {
                endpoints.addAll(parseEndpoints(content));
            }
        }
        return endpoints;
    }

    public List<EntityInfo> extractEntities(List<GitHubFileResponse> files) {
        List<EntityInfo> entities = new ArrayList<>();
        for (GitHubFileResponse file : files) {
            String content = file.content();
            if (file.path().endsWith(".java") && content.contains("@Entity")) {
                entities.add(parseEntity(content));
            }
        }
        return entities;
    }

    private List<EndpointInfo> parseEndpoints(String content) {
        List<EndpointInfo> list = new ArrayList<>();
        matchAndAdd(content, GET_MAPPING, "GET", list);
        matchAndAdd(content, POST_MAPPING, "POST", list);
        matchAndAdd(content, REQUEST_MAPPING, "ALL", list);
        return list;
    }

    private void matchAndAdd(String content, Pattern pattern, String method, List<EndpointInfo> list) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            list.add(EndpointInfo.builder()
                    .method(method)
                    .url(matcher.group(1))
                    .build());
        }
    }

    private EntityInfo parseEntity(String content) {
        Matcher nameMatcher = ENTITY_NAME.matcher(content);
        String name = nameMatcher.find() ? nameMatcher.group(1) : "Unknown";

        List<String> fields = new ArrayList<>();
        Matcher fieldMatcher = FIELD_NAME.matcher(content);
        while (fieldMatcher.find()) {
            fields.add(fieldMatcher.group(1));
        }

        return EntityInfo.builder()
                .name(name)
                .fields(fields)
                .build();
    }
}
