package com.autoreadme.service;

import com.autoreadme.api.dto.EndpointInfo;
import com.autoreadme.api.dto.EntityInfo;
import com.autoreadme.client.github.model.GitHubFileResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeProfilerTest {

    private final CodeProfiler codeProfiler = new CodeProfiler();

    @Test
    void testEndpointExtraction() {
        String content = "@RestController\n" +
                         "public class TestController {\n" +
                         "    @GetMapping(\"/api/test\")\n" +
                         "    public String test() { return \"ok\"; }\n" +
                         "    @PostMapping(value = \"/api/save\")\n" +
                         "    public void save() { }\n" +
                         "}";
        GitHubFileResponse file = new GitHubFileResponse("TestController.java", content);

        List<EndpointInfo> endpoints = codeProfiler.extractEndpoints(List.of(file));

        assertThat(endpoints).hasSize(2);
        assertThat(endpoints).extracting(EndpointInfo::getUrl)
                .containsExactlyInAnyOrder("/api/test", "/api/save");
    }

    @Test
    void testEntityExtraction() {
        String content = "@Entity\n" +
                         "public class User {\n" +
                         "    @Id\n" +
                         "    private Long id;\n" +
                         "    private String name;\n" +
                         "}";
        GitHubFileResponse file = new GitHubFileResponse("User.java", content);

        List<EntityInfo> entities = codeProfiler.extractEntities(List.of(file));

        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getName()).isEqualTo("User");
        assertThat(entities.get(0).getFields()).contains("id", "name");
    }
}
