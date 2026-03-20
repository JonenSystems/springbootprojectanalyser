package com.example.springbootprojectanalyser.model.dto;

/**
 * 解析証跡情報
 */
public record EvidenceDto(
    String sourcePolicy,
    String generatedBy
) {
}
