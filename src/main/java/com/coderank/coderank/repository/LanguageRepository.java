package com.coderank.coderank.repository;

import com.coderank.coderank.entity.Language;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LanguageRepository extends JpaRepository<Language, Long> {
}