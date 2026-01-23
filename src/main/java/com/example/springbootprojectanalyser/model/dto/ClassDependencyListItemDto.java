package com.example.springbootprojectanalyser.model.dto;

/**
 * 依存関係一覧表示用DTO
 */
public record ClassDependencyListItemDto(
    String dependencyKindCode,
    String dependencyKindName,
    String parentClassName,
    String childClassName
) {
}
