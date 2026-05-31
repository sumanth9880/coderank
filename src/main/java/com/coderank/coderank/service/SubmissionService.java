package com.coderank.coderank.service;

import com.coderank.coderank.dto.SubmissionDtos.CreateSubmissionRequest;
import com.coderank.coderank.entity.*;
import com.coderank.coderank.repository.LanguageRepository;
import com.coderank.coderank.repository.SubmissionRepository;
import com.coderank.coderank.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final LanguageRepository languageRepository;
    private final UserRepository userRepository;

    public SubmissionService(SubmissionRepository submissionRepository,
                             LanguageRepository languageRepository,
                             UserRepository userRepository) {
        this.submissionRepository = submissionRepository;
        this.languageRepository = languageRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Submission create(UUID userId, CreateSubmissionRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        Language lang = languageRepository.findById(req.languageId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown language"));

        Submission s = Submission.builder()
                .user(user)
                .language(lang)
                .sourceCode(req.sourceCode())
                .stdin(req.stdin())
                .status(SubmissionStatus.QUEUED)
                .build();
        return submissionRepository.save(s);
    }

    @Transactional(readOnly = true)
    public Submission getForUser(UUID submissionId, UUID userId) {
        return submissionRepository.findByIdAndUser_Id(submissionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
    }

    @Transactional(readOnly = true)
    public Page<Submission> listForUser(UUID userId, Pageable pageable) {
        return submissionRepository.findByUser_IdOrderByCreatedAtDesc(userId, pageable);
    }
}