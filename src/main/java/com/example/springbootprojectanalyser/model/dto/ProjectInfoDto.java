package com.example.springbootprojectanalyser.model.dto;

/**
 * 解析対象プロジェクト情報
 */
public record ProjectInfoDto(
    String name,
    String root,
    String analyzedAt,
    String analyzedFrom
) {
}
