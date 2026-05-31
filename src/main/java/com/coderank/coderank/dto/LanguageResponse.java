package com.coderank.coderank.dto;

import com.coderank.coderank.entity.Language;

public record LanguageResponse(Long id, String name, String version) {
    public static LanguageResponse from(Language l) {
        return new LanguageResponse(l.getId(), l.getName(), l.getVersion());
    }
}