package com.example.springbootprojectanalyser.model.dto;

/**
 * DBテーブル情報
 */
public record TableInfoDto(
    String name,
    String schema,
    String logicalName
) {
}
