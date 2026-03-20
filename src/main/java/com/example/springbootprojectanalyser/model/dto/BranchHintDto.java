package com.example.springbootprojectanalyser.model.dto;

/**
 * 分岐ヒント情報
 */
public record BranchHintDto(
    String location,
    String conditionText,
    String note
) {
}
