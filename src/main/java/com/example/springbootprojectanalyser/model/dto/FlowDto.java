package com.example.springbootprojectanalyser.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * シーケンス図のフロー情報
 * <p>type の例: call, return_view, session, db, alt_hint, dependency（依存関係一覧由来。label は 依存種類コード_依存種類名。同一親子は集約しカンマ区切り）</p>
 */
public record FlowDto(
    String type,
    String from,
    String to,
    String label,
    String action,
    String key,
    String op,
    String query,
    String template,
    String location,
    String conditionText,
    String cond,
    DbNoteDto note,
    @JsonProperty("then") List<FlowDto> thenSteps,
    @JsonProperty("else") List<FlowDto> elseSteps,
    List<FlowDto> steps
) {
}
