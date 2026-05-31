package com.coderank.coderank.service;

import com.coderank.coderank.entity.SubmissionStatus;

public record ExecutionResult(
        SubmissionStatus status,
        String stdout,
        String stderr,
        Integer exitCode,
        long execTimeMs,
        Long memoryKb
) {}