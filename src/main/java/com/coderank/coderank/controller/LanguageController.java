package com.coderank.coderank.controller;

import com.coderank.coderank.dto.LanguageResponse;
import com.coderank.coderank.repository.LanguageRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/languages")
public class LanguageController {

    private final LanguageRepository languageRepository;

    public LanguageController(LanguageRepository languageRepository) {
        this.languageRepository = languageRepository;
    }

    @GetMapping
    public List<LanguageResponse> list() {
        return languageRepository.findAll().stream()
                .map(LanguageResponse::from)
                .toList();
    }
}