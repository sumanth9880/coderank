package com.coderank.coderank.controller;

import com.coderank.coderank.dto.SubmissionDtos.*;
import com.coderank.coderank.entity.Submission;
import com.coderank.coderank.service.SubmissionExecutionRunner;
import com.coderank.coderank.service.SubmissionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final SubmissionExecutionRunner runner;

    public SubmissionController(SubmissionService submissionService,
                                SubmissionExecutionRunner runner) {
        this.submissionService = submissionService;
        this.runner = runner;
    }

    @PostMapping
    public ResponseEntity<SubmissionResponse> create(@AuthenticationPrincipal String userId,
                                                     @RequestBody @Valid CreateSubmissionRequest req) {
        Submission saved = submissionService.create(UUID.fromString(userId), req);
        // Tx has committed by now — safe to dispatch async.
        runner.runAsync(saved.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SubmissionResponse.from(saved));
    }

    @GetMapping("/{id}")
    public SubmissionResponse get(@AuthenticationPrincipal String userId,
                                  @PathVariable UUID id) {
        return SubmissionResponse.from(submissionService.getForUser(id, UUID.fromString(userId)));
    }

    @GetMapping
    public List<SubmissionResponse> list(@AuthenticationPrincipal String userId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return submissionService.listForUser(
                        UUID.fromString(userId),
                        PageRequest.of(page, Math.min(size, 100)))
                .map(SubmissionResponse::from)
                .toList();
    }
}