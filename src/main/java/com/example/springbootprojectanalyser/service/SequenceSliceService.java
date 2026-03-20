package com.example.springbootprojectanalyser.service;

import com.example.springbootprojectanalyser.model.dto.ClassDiagramDto;
import com.example.springbootprojectanalyser.model.dto.EndpointDto;
import com.example.springbootprojectanalyser.model.dto.SequenceInputDto;

/**
 * シーケンス図作成用JSON生成サービス
 */
public interface SequenceSliceService {
    /**
     * シーケンス図作成用の入力JSONを生成する
     *
     * @param classDiagram クラス図作成結果
     * @param endpoint 起点エンドポイント
     * @param projectRootPath 対象プロジェクトルートパス
     * @return sequence_input.jsonに相当するDTO
     */
    SequenceInputDto generateSequenceInput(ClassDiagramDto classDiagram, EndpointDto endpoint, String projectRootPath);
}
