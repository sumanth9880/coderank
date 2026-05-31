package com.coderank.coderank.dto;

import com.coderank.coderank.entity.Submission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public class SubmissionDtos {

    public record CreateSubmissionRequest(
            @NotNull Long languageId,
            @NotBlank @Size(max = 100_000, message = "Source code too large (max 100KB)") String sourceCode,
            @Size(max = 10_000, message = "Stdin too large (max 10KB)") String stdin
    ) {}

    public record SubmissionResponse(
            UUID id,
            String language,
            String status,
            String stdout,
            String stderr,
            Integer exitCode,
            Long execTimeMs,
            Long memoryKb,
            Instant createdAt,
            Instant completedAt
    ) {
        public static SubmissionResponse from(Submission s) {
            return new SubmissionResponse(
                    s.getId(),
                    s.getLanguage().getName() + " " + s.getLanguage().getVersion(),
                    s.getStatus().name(),
                    s.getStdout(),
                    s.getStderr(),
                    s.getExitCode(),
                    s.getExecTimeMs(),
                    s.getMemoryKb(),
                    s.getCreatedAt(),
                    s.getCompletedAt()
            );
        }
    }
}