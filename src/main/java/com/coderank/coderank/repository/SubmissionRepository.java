package com.coderank.coderank.repository;

import com.coderank.coderank.entity.Submission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    @EntityGraph(attributePaths = {"language"})
    Page<Submission> findByUser_IdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"language"})
    Optional<Submission> findByIdAndUser_Id(UUID id, UUID userId);
}