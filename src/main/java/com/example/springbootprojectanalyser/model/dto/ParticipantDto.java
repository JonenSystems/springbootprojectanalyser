package com.example.springbootprojectanalyser.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * シーケンス図の参加者情報
 */
public record ParticipantDto(
    String id,
    String kind,
    String layer,
    String display,
    String fqcn,
    @JsonProperty("interface") String interfaceName,
    String impl,
    TableInfoDto table
) {
}
