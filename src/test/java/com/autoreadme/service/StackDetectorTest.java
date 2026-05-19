package com.autoreadme.service;

import com.autoreadme.api.dto.DetectedStack;
import com.autoreadme.api.dto.TechStack;
import com.autoreadme.client.github.model.GitHubFileResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StackDetectorTest {

    private final StackDetector stackDetector = new StackDetector();

    @Test
    @DisplayName("Spring Boot Gradle 프로젝트 기술 스택 식별 테스트")
    void detectSpringBootGradle() {
        GitHubFileResponse buildGradle = new GitHubFileResponse("build.gradle", 
            "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }");
        
        Flux<GitHubFileResponse> files = Flux.just(buildGradle);

        StepVerifier.create(stackDetector.detectStacks(files))
                .assertNext(stacks -> {
                    assertThat(stacks).extracting(DetectedStack::getStack)
                            .contains(TechStack.GRADLE, TechStack.SPRING_BOOT, TechStack.JAVA);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("React npm 프로젝트 기술 스택 식별 테스트")
    void detectReactNpm() {
        GitHubFileResponse packageJson = new GitHubFileResponse("package.json", 
            "{ \"dependencies\": { \"react\": \"^18.2.0\", \"typescript\": \"^4.9.5\" } }");
        
        Flux<GitHubFileResponse> files = Flux.just(packageJson);

        StepVerifier.create(stackDetector.detectStacks(files))
                .assertNext(stacks -> {
                    assertThat(stacks).extracting(DetectedStack::getStack)
                            .contains(TechStack.NPM, TechStack.REACT, TechStack.TYPESCRIPT);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Python 프로젝트 기술 스택 식별 테스트")
    void detectPython() {
        GitHubFileResponse reqs = new GitHubFileResponse("requirements.txt", 
            "fastapi==0.100.0\nuvicorn\nsqlalchemy");
        
        Flux<GitHubFileResponse> files = Flux.just(reqs);

        StepVerifier.create(stackDetector.detectStacks(files))
                .assertNext(stacks -> {
                    assertThat(stacks).extracting(DetectedStack::getStack)
                            .contains(TechStack.PYTHON, TechStack.FASTAPI);
                })
                .verifyComplete();
    }
}
