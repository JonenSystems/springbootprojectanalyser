package com.example.springbootprojectanalyser.model.dto;

/**
 * DB操作メタ情報
 */
public record DbNoteDto(
    String dbType,
    String schema,
    String tableLogical
) {
}
