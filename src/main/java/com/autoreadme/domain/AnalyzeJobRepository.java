package com.autoreadme.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyzeJobRepository extends JpaRepository<AnalyzeJobEntity, String> {
}
