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
    void testEndpointExtractionWithPrefix() {
        String content = "@RestController\n" +
                         "@RequestMapping(\"/api/v1\")\n" +
                         "public class TestController {\n" +
                         "    @GetMapping(\"/test\")\n" +
                         "    public String test() { return \"ok\"; }\n" +
                         "    @PutMapping(\"/update\")\n" +
                         "    public void update() { }\n" +
                         "    @DeleteMapping(\"/delete\")\n" +
                         "    public void delete() { }\n" +
                         "}";
        GitHubFileResponse file = new GitHubFileResponse("TestController.java", content);

        List<EndpointInfo> endpoints = codeProfiler.extractEndpoints(List.of(file));

        assertThat(endpoints).hasSize(3);
        assertThat(endpoints).extracting(EndpointInfo::getUrl)
                .containsExactlyInAnyOrder("/api/v1/test", "/api/v1/update", "/api/v1/delete");
        assertThat(endpoints).extracting(EndpointInfo::getMethod)
                .contains("GET", "PUT", "DELETE");
    }

    @Test
    void testRequestMappingMethodAndEndpointMetadataExtraction() {
        String content = "@RestController\n" +
                         "@RequestMapping(path = \"/api/members\")\n" +
                         "public class MemberController {\n" +
                         "    @RequestMapping(value = \"/{id}\", method = RequestMethod.GET)\n" +
                         "    public ResponseEntity<MemberResponse> find(@PathVariable Long id) { return null; }\n" +
                         "    @PostMapping(\"/signup\")\n" +
                         "    public MemberResponse signup(@RequestBody MemberCreateRequest request) { return null; }\n" +
                         "}";
        GitHubFileResponse file = new GitHubFileResponse("src/main/java/com/test/MemberController.java", content);

        List<EndpointInfo> endpoints = codeProfiler.extractEndpoints(List.of(file));

        assertThat(endpoints).hasSize(2);
        assertThat(endpoints).anySatisfy(endpoint -> {
            assertThat(endpoint.getMethod()).isEqualTo("GET");
            assertThat(endpoint.getUrl()).isEqualTo("/api/members/{id}");
            assertThat(endpoint.getControllerName()).isEqualTo("MemberController");
            assertThat(endpoint.getMethodName()).isEqualTo("find");
            assertThat(endpoint.getFilePath()).isEqualTo("src/main/java/com/test/MemberController.java");
            assertThat(endpoint.getResponseDto()).isEqualTo("MemberResponse");
        });
        assertThat(endpoints).anySatisfy(endpoint -> {
            assertThat(endpoint.getMethod()).isEqualTo("POST");
            assertThat(endpoint.getUrl()).isEqualTo("/api/members/signup");
            assertThat(endpoint.getRequestDto()).isEqualTo("MemberCreateRequest");
            assertThat(endpoint.getResponseDto()).isEqualTo("MemberResponse");
        });
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

    @Test
    void testEntityFieldTypeTableAndRelationExtraction() {
        String content = "@Entity\n" +
                         "@Table(name = \"orders\")\n" +
                         "public class Order {\n" +
                         "    @Id\n" +
                         "    private Long id;\n" +
                         "    @Column(name = \"order_name\")\n" +
                         "    private String name;\n" +
                         "    @ManyToOne\n" +
                         "    private Member member;\n" +
                         "}";
        GitHubFileResponse file = new GitHubFileResponse("src/main/java/com/test/Order.java", content);

        List<EntityInfo> entities = codeProfiler.extractEntities(List.of(file));

        assertThat(entities).hasSize(1);
        EntityInfo entity = entities.get(0);
        assertThat(entity.getName()).isEqualTo("Order");
        assertThat(entity.getTableName()).isEqualTo("orders");
        assertThat(entity.getFilePath()).isEqualTo("src/main/java/com/test/Order.java");
        assertThat(entity.getFieldDetails()).extracting("name", "type", "columnName")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("id", "Long", null),
                        org.assertj.core.groups.Tuple.tuple("name", "String", "order_name"),
                        org.assertj.core.groups.Tuple.tuple("member", "Member", null)
                );
        assertThat(entity.getRelationships()).extracting("fieldName", "relationType", "targetEntity")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("member", "ManyToOne", "Member"));
    }
}
