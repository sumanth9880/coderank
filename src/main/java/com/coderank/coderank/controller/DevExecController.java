package com.coderank.coderank.controller;

import com.coderank.coderank.repository.LanguageRepository;
import com.coderank.coderank.service.CodeExecutorService;
import com.coderank.coderank.service.ExecutionResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// TEMPORARY — remove in Layer 6. No auth, just for testing the executor.
@RestController
@RequestMapping("/api/v1/_dev")
public class DevExecController {

    private final CodeExecutorService executor;
    private final LanguageRepository languages;

    public DevExecController(CodeExecutorService executor, LanguageRepository languages) {
        this.executor = executor;
        this.languages = languages;
    }

    public record DevRunRequest(Long languageId, String sourceCode, String stdin) {}

    @PostMapping("/run")
    public ExecutionResult run(@RequestBody DevRunRequest req) {
        var lang = languages.findById(req.languageId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Language not found"));
        return executor.execute(lang, req.sourceCode(), req.stdin());
    }
}