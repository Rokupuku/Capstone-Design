package com.autoreadme.api.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TechStack {
    // Languages
    JAVA("Java"),
    KOTLIN("Kotlin"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    PYTHON("Python"),

    // Frameworks
    SPRING_BOOT("Spring Boot"),
    REACT("React"),
    NEXT_JS("Next.js"),
    VUE("Vue"),
    EXPRESS("Express"),
    DJANGO("Django"),
    FASTAPI("FastAPI"),

    // Build Tools
    GRADLE("Gradle"),
    MAVEN("Maven"),
    NPM("npm"),
    YARN("Yarn"),
    PNPM("pnpm"),

    // Databases
    MYSQL("MySQL"),
    POSTGRESQL("PostgreSQL"),
    REDIS("Redis"),
    MONGODB("MongoDB"),
    H2("H2"),

    // Others
    DOCKER("Docker"),
    GITHUB_ACTIONS("GitHub Actions");

    private final String displayName;
}
