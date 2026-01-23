package com.example.springbootprojectanalyser.model.dto;

import java.util.List;
import java.util.Map;

/**
 * クラス図DTO
 */
public record ClassDiagramDto(
    String classDiagramText,
    List<ClassInfoDto> targetClasses,
    Map<String, List<MemberInfoDto>> classMemberMap,
    Map<String, Map<String, List<String>>> dependencyMap,
    Map<String, String> classFilePaths,
    List<ClassDependencyListItemDto> dependencyList
) {
}

