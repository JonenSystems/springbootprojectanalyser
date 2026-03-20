package com.example.springbootprojectanalyser.model.dto;

/**
 * 起点エンドポイント情報
 */
public record EntryPointDto(
    String httpMethod,
    String path,
    String handler
) {
}
