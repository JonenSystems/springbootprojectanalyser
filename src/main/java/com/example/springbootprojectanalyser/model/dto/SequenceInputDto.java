package com.example.springbootprojectanalyser.model.dto;

import java.util.List;

/**
 * シーケンス図作成用の入力DTO（sequence_input.json）
 */
public record SequenceInputDto(
    ProjectInfoDto project,
    EntryPointDto entry,
    List<String> participantOrder,
    List<ParticipantDto> participants,
    List<FlowDto> flows,
    EvidenceDto evidence,
    List<BranchHintDto> branchHints
) {
}
