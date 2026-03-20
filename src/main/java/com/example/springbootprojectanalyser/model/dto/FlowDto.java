package com.example.springbootprojectanalyser.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * シーケンス図のフロー情報
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
