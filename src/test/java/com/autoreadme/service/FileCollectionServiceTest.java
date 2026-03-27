package com.autoreadme.service;

import com.autoreadme.client.github.GitHubClient;
import com.autoreadme.client.github.model.GitTreeItem;
import com.autoreadme.client.github.model.GitTreeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileCollectionServiceTest {

    @Mock
    private GitHubClient gitHubClient;

    @InjectMocks
    private FileCollectionService fileCollectionService;

    @Test
    void shouldCollectTargetFiles() {
        // Given
        String owner = "test-owner";
        String repo = "test-repo";
        String branch = "main";

        GitTreeItem item1 = new GitTreeItem("src/main/java/com/test/Controller.java", "100644", "blob", "sha1", "url1");
        GitTreeItem item2 = new GitTreeItem("README.md", "100644", "blob", "sha2", "url2");
        GitTreeResponse treeResponse = new GitTreeResponse("root-sha", List.of(item1, item2), false);

        when(gitHubClient.getTree(owner, repo, branch)).thenReturn(Mono.just(treeResponse));
        when(gitHubClient.getFileContent(anyString(), anyString(), anyString())).thenReturn(Mono.just("public class Controller {}"));

        // When & Then
        StepVerifier.create(fileCollectionService.collectTargetFiles(owner, repo, branch))
                .expectNextMatches(file -> file.path().equals("src/main/java/com/test/Controller.java") && file.content().equals("public class Controller {}"))
                .verifyComplete();
    }
}