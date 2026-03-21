package com.example.springbootprojectanalyser.service;

import com.example.springbootprojectanalyser.model.dto.SequenceInputDto;

/**
 * シーケンス図（Mermaid）生成サービス
 */
public interface SequenceDiagramMermaidService {
    /**
     * SequenceInputDto を Mermaid の sequenceDiagram 文字列へ変換する。
     *
     * @param sequenceInput シーケンス図作成用入力
     * @return Mermaid sequenceDiagram テキスト
     */
    String generateSequenceDiagramText(SequenceInputDto sequenceInput);
}

