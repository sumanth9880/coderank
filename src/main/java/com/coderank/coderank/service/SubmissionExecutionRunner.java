package com.coderank.coderank.service;

import com.coderank.coderank.entity.Submission;
import com.coderank.coderank.entity.SubmissionStatus;
import com.coderank.coderank.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SubmissionExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(SubmissionExecutionRunner.class);

    private final SubmissionRepository submissionRepository;
    private final CodeExecutorService executor;

    public SubmissionExecutionRunner(SubmissionRepository submissionRepository,
                                     CodeExecutorService executor) {
        this.submissionRepository = submissionRepository;
        this.executor = executor;
    }

    /**
     * Runs on a background thread (Spring's task executor). Marks the
     * submission RUNNING, executes the code, then writes the result back.
     */
    @Async
    @Transactional
    public void runAsync(UUID submissionId) {
        Submission s = submissionRepository.findById(submissionId).orElse(null);
        if (s == null) {
            log.warn("Submission {} vanished before execution", submissionId);
            return;
        }

        // Flip to RUNNING so polls see the transition.
        s.setStatus(SubmissionStatus.RUNNING);
        submissionRepository.saveAndFlush(s);

        ExecutionResult result;
        try {
            // Inside tx, so lazy-loaded language fields work.
            result = executor.execute(s.getLanguage(), s.getSourceCode(), s.getStdin());
        } catch (Exception e) {
            log.error("Unexpected error executing submission {}", submissionId, e);
            result = new ExecutionResult(SubmissionStatus.ERROR, "",
                    "Internal error: " + e.getMessage(), null, 0, null);
        }

        s.setStatus(result.status());
        s.setStdout(result.stdout());
        s.setStderr(result.stderr());
        s.setExitCode(result.exitCode());
        s.setExecTimeMs(result.execTimeMs());
        s.setMemoryKb(result.memoryKb());
        s.setCompletedAt(Instant.now());
        submissionRepository.save(s);
    }
}