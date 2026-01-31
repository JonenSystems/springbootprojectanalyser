package com.example.springbootprojectanalyser.service.impl;

import com.example.springbootprojectanalyser.model.dto.*;
import com.example.springbootprojectanalyser.model.entity.*;
import com.example.springbootprojectanalyser.repository.*;
import com.example.springbootprojectanalyser.service.ClassDiagramService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * クラス図生成サービス実装クラス
 */
@Service
public class ClassDiagramServiceImpl implements ClassDiagramService {

    private static final int MAX_DEPTH = 10;

    private final EndpointRepository endpointRepository;
    private final ClassDependencyRepository classDependencyRepository;
    private final MemberRepository memberRepository;
    private final ProjectRepository projectRepository;

    public ClassDiagramServiceImpl(
            EndpointRepository endpointRepository,
            ClassDependencyRepository classDependencyRepository,
            MemberRepository memberRepository,
            ProjectRepository projectRepository) {
        this.endpointRepository = endpointRepository;
        this.classDependencyRepository = classDependencyRepository;
        this.memberRepository = memberRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ClassDiagramDto generateClassDiagram(java.util.UUID selectedEndpointId, Long projectId) {
        // SPC-201.002-001: 起点クラスの選択
        Endpoint endpoint = endpointRepository.findById(selectedEndpointId.toString())
                .orElseThrow(() -> new IllegalArgumentException("エンドポイントが見つかりません: " + selectedEndpointId));

        ClassEntity startClass = endpoint.getClassEntity();

        // SPC-201.003-001: 対象クラスの抽出
        Set<ClassEntity> targetClasses = extractTargetClasses(startClass, projectId);
        List<ClassInfoDto> targetClassList = targetClasses.stream()
                .map(c -> new ClassInfoDto(c.getId(), c.getFullQualifiedName(), c.getSimpleName()))
                .collect(Collectors.toList());

        // 依存関係マップの作成
        Map<String, Map<String, List<String>>> dependencyMap = buildDependencyMap(targetClasses, projectId);
        // 依存関係一覧の作成
        List<ClassDependencyListItemDto> dependencyList = buildDependencyList(targetClasses, projectId);

        // インターフェースクラスのセットを作成（実装関係「001_002」のターゲットになっているクラス）
        Set<String> interfaceClassFqns = identifyInterfaceClasses(targetClasses, projectId);

        // 起点クラスのFQNを保持（コントローラクラスを明示的に表現するため）
        String startClassFqn = startClass.getFullQualifiedName();

        // エンドポイント情報を取得（URIとHTTPメソッド）
        String endpointUri = endpoint.getUri();
        String httpMethod = endpoint.getHttpMethod() != null ? endpoint.getHttpMethod().getMethodName() : "";

        // SPC-201.004-001: クラスダイアログ記載事項の抽出
        // 注意: 現在の実装では、クラス依存関係解析時にメンバー情報は保存されていないため、
        // 依存関係から推測してメンバー情報を生成します
        Map<String, List<MemberInfoDto>> classMemberMap = extractClassMembers(targetClasses, projectId);

        // プロジェクト情報を取得（ファイルパス生成のため）
        com.example.springbootprojectanalyser.model.entity.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("プロジェクトが見つかりません"));

        // 関連ファイルパス情報を生成
        Map<String, String> classFilePaths = generateClassFilePaths(targetClasses, project.getRootPath());

        // SPC-201.005-001: クラス図の書式生成
        String classDiagramText = generateMermaidClassDiagram(targetClassList, classMemberMap, dependencyMap,
                interfaceClassFqns, startClassFqn, endpointUri, httpMethod);

        return new ClassDiagramDto(classDiagramText, targetClassList, classMemberMap, dependencyMap, classFilePaths,
                dependencyList);
    }

    /**
     * 対象クラスを抽出する（再帰的に依存関係を追跡）
     * SPC-201.003-001に準拠
     * インターフェースクラスも含める（逆方向の依存関係も追跡）
     */
    private Set<ClassEntity> extractTargetClasses(ClassEntity startClass, Long projectId) {
        // 訪問済みセットを初期化（対象クラスリストとして使用）
        Set<ClassEntity> visited = new HashSet<>();
        // 起点クラスをキューに追加
        Queue<ClassEntity> queue = new LinkedList<>();
        queue.offer(startClass);
        // 起点クラスを対象クラスリストに追加
        visited.add(startClass);

        int depth = 0;
        // キューが空になるまで、または最大深度に達するまで処理
        while (!queue.isEmpty() && depth < MAX_DEPTH) {
            int levelSize = queue.size();
            depth++;

            for (int i = 0; i < levelSize; i++) {
                // キューからクラスを取得
                ClassEntity current = queue.poll();
                if (current == null) {
                    continue;
                }

                // 順方向の依存関係を取得（class_dependenciesテーブルを参照）
                List<ClassDependency> outgoingDependencies = classDependencyRepository
                        .findBySourceClass_Id(current.getId());

                // 順方向の依存関係を処理
                if (outgoingDependencies != null && !outgoingDependencies.isEmpty()) {
                    for (ClassDependency dep : outgoingDependencies) {
                        // プロジェクト内のクラスで、targetClassが解決されているもののみ処理
                        if (dep.getTargetClass() != null) {
                            ClassEntity target = dep.getTargetClass();
                            Long targetProjectId = target.getProject() != null ? target.getProject().getId() : null;

                            if (targetProjectId != null && targetProjectId.equals(projectId)) {
                                // テストクラスを除外（クラス名がTestで終わるもの）
                                if (isTestClass(target)) {
                                    continue;
                                }

                                // 未訪問の依存先クラスをキューに追加
                                if (!visited.contains(target)) {
                                    visited.add(target);
                                    queue.offer(target);
                                }
                            }
                        }
                    }
                }

                // 逆方向の依存関係を取得（インターフェースクラスを含めるため）
                // 例：実装クラスがインターフェースを実装している場合、そのインターフェースも含める
                List<ClassDependency> incomingDependencies = classDependencyRepository
                        .findByTargetClass_Id(current.getId());

                // 逆方向の依存関係を処理（実装関係「001_002」のみを対象）
                if (incomingDependencies != null && !incomingDependencies.isEmpty()) {
                    for (ClassDependency dep : incomingDependencies) {
                        // 実装関係（001_002）のみを対象とする
                        if (dep.getDependencyKind() == null ||
                                !"001_002".equals(dep.getDependencyKind().getCode())) {
                            continue;
                        }

                        // プロジェクト内のクラスで、sourceClassが解決されているもののみ処理
                        if (dep.getSourceClass() != null) {
                            ClassEntity source = dep.getSourceClass();

                            // テストクラスを除外（クラス名がTestで終わるもの）
                            if (isTestClass(source)) {
                                continue;
                            }

                            Long sourceProjectId = source.getProject() != null ? source.getProject().getId() : null;

                            if (sourceProjectId != null && sourceProjectId.equals(projectId)) {
                                // 未訪問の依存元クラスをキューに追加
                                // 実装関係（001_002）の場合のみ、インターフェースと実装クラスの両方を含める
                                if (!visited.contains(source)) {
                                    visited.add(source);
                                    queue.offer(source);
                                }
                            }
                        }
                    }
                }
            }
        }

        return visited;
    }

    /**
     * テストクラスかどうかを判定する
     * クラス名がTestで終わるもの、またはパッケージ名にtestが含まれるものをテストクラスとみなす
     */
    private boolean isTestClass(ClassEntity classEntity) {
        if (classEntity == null) {
            return false;
        }

        String simpleName = classEntity.getSimpleName();
        if (simpleName != null && simpleName.endsWith("Test")) {
            return true;
        }

        String fullQualifiedName = classEntity.getFullQualifiedName();
        if (fullQualifiedName != null) {
            // パッケージ名にtestが含まれるかチェック
            String lowerFqn = fullQualifiedName.toLowerCase();
            if (lowerFqn.contains(".test.") || lowerFqn.startsWith("test.")) {
                return true;
            }
        }

        return false;
    }

    /**
     * インターフェースクラスを識別する
     * 実装関係（依存種類コード「001_002」）のターゲットになっているクラスをインターフェースとみなす
     */
    private Set<String> identifyInterfaceClasses(Set<ClassEntity> classes, Long projectId) {
        Set<String> interfaceFqns = new HashSet<>();

        for (ClassEntity classEntity : classes) {
            // このクラスをターゲットとする実装関係（001_002）を検索
            List<ClassDependency> incomingDependencies = classDependencyRepository
                    .findByTargetClass_Id(classEntity.getId());

            for (ClassDependency dep : incomingDependencies) {
                // 実装関係（001_002）のみを対象
                if (dep.getDependencyKind() != null &&
                        "001_002".equals(dep.getDependencyKind().getCode()) &&
                        dep.getSourceClass() != null &&
                        dep.getSourceClass().getProject() != null &&
                        dep.getSourceClass().getProject().getId().equals(projectId) &&
                        classes.contains(dep.getSourceClass())) {
                    // このクラスが実装関係のターゲットになっている場合、インターフェースとみなす
                    interfaceFqns.add(classEntity.getFullQualifiedName());
                    break; // 1つでも見つかればインターフェースと判定
                }
            }
        }

        return interfaceFqns;
    }

    /**
     * 依存関係マップを構築する
     * 同じソース→ターゲットの組み合わせをまとめて、依存の種類をリストとして保持
     * 戻り値: ソースFQN -> ターゲットFQN -> 依存の種類ラベルのリスト
     */
    private Map<String, Map<String, List<String>>> buildDependencyMap(Set<ClassEntity> classes, Long projectId) {
        // ソースFQN -> ターゲットFQN -> 依存の種類ラベルのリスト
        Map<String, Map<String, List<String>>> dependencyMap = new HashMap<>();

        for (ClassEntity sourceClass : classes) {
            List<ClassDependency> dependencies = classDependencyRepository
                    .findBySourceClass_Id(sourceClass.getId());

            String sourceFqn = sourceClass.getFullQualifiedName();
            Map<String, List<String>> targetMap = dependencyMap.computeIfAbsent(sourceFqn, k -> new HashMap<>());

            for (ClassDependency dep : dependencies) {
                String dependencyKindCode = dep.getDependencyKind() != null ? dep.getDependencyKind().getCode() : "";
                String dependencyKindDescription = dep.getDependencyKind() != null
                        ? dep.getDependencyKind().getDescription()
                        : "";
                String dependencyLabel = formatDependencyLabel(dependencyKindCode, dependencyKindDescription);

                if (dep.getTargetClass() != null &&
                        dep.getTargetClass().getProject().getId().equals(projectId) &&
                        classes.contains(dep.getTargetClass())) {

                    String targetFqn = dep.getTargetClass().getFullQualifiedName();
                    if (dependencyLabel != null && !dependencyLabel.isEmpty()) {
                        targetMap.computeIfAbsent(targetFqn, k -> new ArrayList<>()).add(dependencyLabel);
                    }
                    continue;
                }

                if ("009_006".equals(dependencyKindCode)) {
                    String targetIdentifier = dep.getTargetIdentifier();
                    if (targetIdentifier != null && targetIdentifier.startsWith("org.springframework.ui.Model")) {
                        String targetFqn = targetIdentifier;
                        int colonIndex = targetIdentifier.indexOf(":");
                        if (colonIndex > 0) {
                            targetFqn = targetIdentifier.substring(0, colonIndex);
                        }
                        if (dependencyLabel != null && !dependencyLabel.isEmpty()) {
                            targetMap.computeIfAbsent(targetFqn, k -> new ArrayList<>()).add(dependencyLabel);
                        }
                    }
                }
            }
        }

        return dependencyMap;
    }

    /**
     * 依存関係一覧を構築する
     */
    private List<ClassDependencyListItemDto> buildDependencyList(Set<ClassEntity> classes, Long projectId) {
        List<ClassDependencyListItemDto> dependencyList = new ArrayList<>();
        Set<Long> classIds = classes.stream()
                .map(ClassEntity::getId)
                .collect(Collectors.toSet());
        Set<String> addedKeys = new HashSet<>();

        for (ClassEntity sourceClass : classes) {
            List<ClassDependency> dependencies = classDependencyRepository
                    .findBySourceClass_Id(sourceClass.getId());

            for (ClassDependency dep : dependencies) {
                String dependencyKindCode = dep.getDependencyKind() != null ? dep.getDependencyKind().getCode() : "";
                String dependencyKindName = dep.getDependencyKind() != null ? dep.getDependencyKind().getDescription()
                        : "";
                String parentClassName = resolveClassName(sourceClass);

                if (dep.getTargetClass() == null || dep.getTargetClass().getProject() == null) {
                    if ("009_006".equals(dependencyKindCode)) {
                        String targetIdentifier = dep.getTargetIdentifier();
                        if (targetIdentifier != null && targetIdentifier.startsWith("org.springframework.ui.Model")) {
                            String childClassName = resolveExternalClassName(targetIdentifier);
                            String uniqueKey = dependencyKindCode + "|" + sourceClass.getFullQualifiedName() + "|"
                                    + targetIdentifier;
                            if (!addedKeys.contains(uniqueKey)) {
                                addedKeys.add(uniqueKey);
                                dependencyList.add(new ClassDependencyListItemDto(
                                        dependencyKindCode,
                                        dependencyKindName,
                                        parentClassName,
                                        childClassName));
                            }
                        }
                    }
                    continue;
                }
                if (!dep.getTargetClass().getProject().getId().equals(projectId)) {
                    continue;
                }
                if (!classIds.contains(dep.getTargetClass().getId())) {
                    continue;
                }

                String childClassName = resolveClassName(dep.getTargetClass());
                String uniqueKey = dependencyKindCode + "|" + sourceClass.getFullQualifiedName() + "|"
                        + dep.getTargetClass().getFullQualifiedName();
                if (addedKeys.contains(uniqueKey)) {
                    continue;
                }
                addedKeys.add(uniqueKey);

                dependencyList.add(new ClassDependencyListItemDto(
                        dependencyKindCode,
                        dependencyKindName,
                        parentClassName,
                        childClassName));
            }
        }

        return dependencyList;
    }

    private String resolveClassName(ClassEntity classEntity) {
        if (classEntity == null) {
            return "";
        }
        String simpleName = classEntity.getSimpleName();
        if (simpleName != null && !simpleName.isEmpty()) {
            return simpleName;
        }
        String fqn = classEntity.getFullQualifiedName();
        return fqn != null ? fqn : "";
    }

    private String resolveExternalClassName(String targetIdentifier) {
        if (targetIdentifier == null || targetIdentifier.isEmpty()) {
            return "";
        }
        String raw = targetIdentifier;
        int colonIndex = targetIdentifier.indexOf(":");
        if (colonIndex > 0) {
            raw = targetIdentifier.substring(0, colonIndex);
        }
        int lastDot = raw.lastIndexOf('.');
        return lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
    }

    /**
     * クラスメンバーを抽出する（参照を受けるメンバーのみ）
     * メンバー情報がデータベースに保存されていない場合は、依存関係から推測する
     */
    private Map<String, List<MemberInfoDto>> extractClassMembers(Set<ClassEntity> classes, Long projectId) {
        Map<String, List<MemberInfoDto>> classMemberMap = new HashMap<>();

        for (ClassEntity classEntity : classes) {
            // このクラスから他のクラスへの依存関係を取得（DIなどで使用されるメンバーを推測）
            List<ClassDependency> outgoingDeps = classDependencyRepository
                    .findBySourceClass_Id(classEntity.getId());

            // メンバーを取得（データベースに保存されている場合）
            List<Member> members = memberRepository.findByClassEntity_Id(classEntity.getId());

            List<MemberInfoDto> referencedMembers = new ArrayList<>();

            if (!members.isEmpty()) {
                // データベースにメンバー情報がある場合
                referencedMembers = members.stream()
                        .map(m -> new MemberInfoDto(
                                m.getName(),
                                m.getReturnType(),
                                m.getVisibility(),
                                m.getMemberType().getCode()))
                        .distinct() // 重複を削除
                        .collect(Collectors.toList());
            } else {
                // メンバー情報がない場合、依存関係から推測
                // すべての依存関係から、このクラス内で使用されている型を抽出
                Set<String> processedTargets = new HashSet<>();
                for (ClassDependency dep : outgoingDeps) {
                    if (dep.getTargetClass() != null &&
                            dep.getTargetClass().getProject().getId().equals(projectId) &&
                            classes.contains(dep.getTargetClass())) {

                        String targetFqn = dep.getTargetClass().getFullQualifiedName();
                        if (processedTargets.contains(targetFqn)) {
                            continue; // 重複を避ける
                        }
                        processedTargets.add(targetFqn);

                        String targetName = dep.getTargetClass().getSimpleName();
                        // フィールド名を推測（クラス名の最初の文字を小文字に）
                        String memberName = Character.toLowerCase(targetName.charAt(0)) + targetName.substring(1);

                        // 依存タイプからメンバータイプを推測
                        String memberType = "FIELD";
                        String depKind = dep.getDependencyKind().getCode();
                        if ("002_003".equals(depKind)) {
                            // コンストラクタDI
                            memberType = "CONSTRUCTOR";
                            memberName = targetName; // コンストラクタはクラス名と同じ
                        } else if ("002_001".equals(depKind)) {
                            // SetterDI
                            memberType = "METHOD";
                            memberName = "set" + targetName;
                        } else if ("002_004".equals(depKind)) {
                            // フィールドDI
                            memberType = "FIELD";
                        } else if ("001_009".equals(depKind) || "001_010".equals(depKind)) {
                            // コンポジション、集合保持
                            memberType = "FIELD";
                        } else {
                            // その他の依存関係もフィールドとして表示
                            memberType = "FIELD";
                        }

                        referencedMembers.add(new MemberInfoDto(
                                memberName,
                                dep.getTargetClass().getFullQualifiedName(),
                                "PRIVATE",
                                memberType));
                    }
                }
                // 重複を削除
                referencedMembers = referencedMembers.stream()
                        .distinct()
                        .collect(Collectors.toList());
            }

            // すべてのクラスにメンバー情報を追加（空の場合は空のリスト）
            classMemberMap.put(classEntity.getFullQualifiedName(), referencedMembers);
        }

        return classMemberMap;
    }

    /**
     * Mermaid形式のクラス図テキストを生成する
     */
    private String generateMermaidClassDiagram(
            List<ClassInfoDto> targetClasses,
            Map<String, List<MemberInfoDto>> classMemberMap,
            Map<String, Map<String, List<String>>> dependencyMap,
            Set<String> interfaceClassFqns,
            String startClassFqn,
            String endpointUri,
            String httpMethod) {

        StringBuilder sb = new StringBuilder();
        sb.append("classDiagram\n");

        // クラス定義を追加
        for (ClassInfoDto classInfo : targetClasses) {
            String className = escapeMermaidName(classInfo.simpleName());
            sb.append("    class ").append(className).append(" {\n");

            // 起点クラス（コントローラクラス）の場合は<<URI(HTTPメソッド)>>を追加
            if (startClassFqn != null && startClassFqn.equals(classInfo.fullQualifiedName())) {
                String endpointLabel = formatEndpointLabel(endpointUri, httpMethod);
                if (endpointLabel != null && !endpointLabel.isEmpty()) {
                    sb.append("        <<").append(escapeMermaidLabel(endpointLabel)).append(">>\n");
                }
            }

            // インターフェースクラスの場合は<<interface>>を追加
            if (interfaceClassFqns.contains(classInfo.fullQualifiedName())) {
                sb.append("        <<interface>>\n");
            }

            // メンバーを追加（重複を削除）
            List<MemberInfoDto> members = classMemberMap.get(classInfo.fullQualifiedName());
            if (members != null && !members.isEmpty()) {
                // フォーマット後のメンバー行の重複を削除
                Set<String> addedMemberLines = new LinkedHashSet<>();
                for (MemberInfoDto member : members) {
                    String memberLine = formatMember(member);
                    if (memberLine != null && !memberLine.trim().isEmpty()) {
                        addedMemberLines.add(memberLine);
                    }
                }
                // 重複を削除したメンバー行を追加
                for (String memberLine : addedMemberLines) {
                    sb.append("        ").append(memberLine).append("\n");
                }
            }

            sb.append("    }\n");
        }

        // 外部クラスの定義を追加（例：Model）
        Set<String> internalFqns = targetClasses.stream()
                .map(ClassInfoDto::fullQualifiedName)
                .collect(Collectors.toSet());
        Set<String> externalFqns = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, List<String>>> sourceEntry : dependencyMap.entrySet()) {
            for (String targetFqn : sourceEntry.getValue().keySet()) {
                if (!internalFqns.contains(targetFqn)) {
                    externalFqns.add(targetFqn);
                }
            }
        }
        for (String externalFqn : externalFqns) {
            String externalName = escapeMermaidName(resolveExternalClassName(externalFqn));
            sb.append("    class ").append(externalName).append("\n");
        }

        // 依存関係の矢印を追加（同じターゲットへの依存の種類をまとめる）
        for (Map.Entry<String, Map<String, List<String>>> sourceEntry : dependencyMap.entrySet()) {
            String sourceFqn = sourceEntry.getKey();
            ClassInfoDto sourceClass = targetClasses.stream()
                    .filter(c -> c.fullQualifiedName().equals(sourceFqn))
                    .findFirst()
                    .orElse(null);

            if (sourceClass == null) {
                continue;
            }

            String sourceName = escapeMermaidName(sourceClass.simpleName());

            // ターゲットごとに依存の種類をまとめる
            for (Map.Entry<String, List<String>> targetEntry : sourceEntry.getValue().entrySet()) {
                String targetFqn = targetEntry.getKey();
                List<String> dependencyLabels = targetEntry.getValue();

                ClassInfoDto targetClass = targetClasses.stream()
                        .filter(c -> c.fullQualifiedName().equals(targetFqn))
                        .findFirst()
                        .orElse(null);
                String targetName = targetClass != null
                        ? escapeMermaidName(targetClass.simpleName())
                        : escapeMermaidName(resolveExternalClassName(targetFqn));

                // 依存の種類ラベルを結合（重複を削除し、<br>で区切る）
                if (dependencyLabels != null && !dependencyLabels.isEmpty()) {
                    // 重複を削除
                    List<String> uniqueLabels = dependencyLabels.stream()
                            .distinct()
                            .collect(Collectors.toList());

                    // <br>で結合
                    String combinedLabel = String.join(",<br>", uniqueLabels);

                    // Mermaid classDiagramの構文: A --> B : label
                    sb.append("    ").append(sourceName)
                            .append(" --> ").append(targetName)
                            .append(" : ").append(escapeMermaidLabel(combinedLabel)).append("\n");
                } else {
                    // ラベルがない場合は通常の矢印
                    sb.append("    ").append(sourceName).append(" --> ").append(targetName).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 依存の種類ラベルをフォーマットする
     * 形式: code_description（例：001_002_実装（implements））
     */
    private String formatDependencyLabel(String code, String description) {
        if (code == null || code.isEmpty()) {
            return description != null ? description : "";
        }
        if (description == null || description.isEmpty()) {
            return code;
        }
        return code + "_" + description;
    }

    /**
     * エンドポイントラベルをフォーマットする
     * 形式: URI(HTTPメソッド)（例：/products(GET)）
     */
    private String formatEndpointLabel(String uri, String httpMethod) {
        if (uri == null || uri.isEmpty()) {
            return httpMethod != null && !httpMethod.isEmpty() ? "(" + httpMethod + ")" : "";
        }
        if (httpMethod == null || httpMethod.isEmpty()) {
            return uri;
        }
        return uri + "(" + httpMethod + ")";
    }

    /**
     * Mermaidラベルで使用できない文字をエスケープする
     */
    private String escapeMermaidLabel(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        // Mermaidラベル内の特殊文字をエスケープ
        // パイプ(|)や改行などはエスケープが必要
        return label.replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    /**
     * メンバーをフォーマットする
     */
    private String formatMember(MemberInfoDto member) {
        if (member == null || member.name() == null || member.name().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 可視性記号
        String visibility = member.visibility();
        if (visibility != null) {
            switch (visibility.toUpperCase()) {
                case "PUBLIC":
                    sb.append("+");
                    break;
                case "PROTECTED":
                    sb.append("#");
                    break;
                case "PRIVATE":
                    sb.append("-");
                    break;
                default:
                    sb.append("~");
            }
        } else {
            sb.append("-");
        }

        // メンバー名
        String memberName = escapeMermaidName(member.name());
        sb.append(memberName);

        // 戻り値型または型情報
        if (member.returnType() != null && !member.returnType().isEmpty()) {
            String returnType = member.returnType();
            // 完全修飾名の場合は最後の部分だけを取得
            if (returnType.contains(".")) {
                returnType = returnType.substring(returnType.lastIndexOf(".") + 1);
            }
            returnType = escapeMermaidName(returnType);

            // メソッドかフィールドかを判定
            if ("METHOD".equals(member.memberTypeCode()) ||
                    "CONSTRUCTOR".equals(member.memberTypeCode())) {
                sb.append("() : ").append(returnType);
            } else {
                sb.append(" : ").append(returnType);
            }
        }

        return sb.toString();
    }

    /**
     * Mermaid記法で使用できない文字をエスケープする
     */
    private String escapeMermaidName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        // 特殊文字をエスケープ（Mermaidで使用できない文字を置換）
        // ただし、クラス名として使用する場合は、アルファベット、数字、アンダースコアのみ許可
        String escaped = name.replaceAll("[^a-zA-Z0-9_]", "_");
        // 連続するアンダースコアを1つに
        escaped = escaped.replaceAll("_{2,}", "_");
        // 先頭・末尾のアンダースコアを削除
        escaped = escaped.replaceAll("^_+|_+$", "");
        return escaped.isEmpty() ? "Unknown" : escaped;
    }

    /**
     * クラスのFQNから実際のファイルパスを検索する
     * プロジェクトルートからJavaファイルを検索し、クラスのFQNと一致するファイルを見つける
     */
    private Map<String, String> generateClassFilePaths(Set<ClassEntity> classes, String projectRootPath) {
        Map<String, String> filePaths = new HashMap<>();
        java.nio.file.Path projectRoot = java.nio.file.Paths.get(projectRootPath);

        try {
            // プロジェクトルートからJavaファイルを収集
            List<java.nio.file.Path> javaFiles = new ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(projectRoot)) {
                paths.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains("target")) // targetディレクトリを除外
                        .filter(p -> !p.toString().contains(".git")) // .gitディレクトリを除外
                        .forEach(javaFiles::add);
            }

            // クラスFQNから実際のファイルパスを検索
            for (ClassEntity classEntity : classes) {
                String fqn = classEntity.getFullQualifiedName();
                if (fqn == null || fqn.isEmpty()) {
                    continue;
                }

                // FQNからパッケージ名とクラス名を分離
                int lastDotIndex = fqn.lastIndexOf('.');
                String packageName = lastDotIndex >= 0 ? fqn.substring(0, lastDotIndex) : "";
                String className = lastDotIndex >= 0 ? fqn.substring(lastDotIndex + 1) : fqn;

                // パッケージ名をディレクトリパスに変換
                String packagePath = packageName.replace('.', '/');

                // 可能なファイルパスを生成（src/main/javaまたはsrc/test/javaを試行）
                List<String> possiblePaths = new ArrayList<>();
                if (packagePath.isEmpty()) {
                    possiblePaths.add("src/main/java/" + className + ".java");
                    possiblePaths.add("src/test/java/" + className + ".java");
                } else {
                    possiblePaths.add("src/main/java/" + packagePath + "/" + className + ".java");
                    possiblePaths.add("src/test/java/" + packagePath + "/" + className + ".java");
                }

                // 実際のファイルを検索
                String foundPath = null;
                for (java.nio.file.Path javaFile : javaFiles) {
                    // プロジェクトルートからの相対パスを取得
                    try {
                        String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');

                        // ファイル名で一致するか確認
                        String fileName = javaFile.getFileName().toString();
                        if (fileName.equals(className + ".java")) {
                            // ファイル内容をパースしてFQNを確認
                            try {
                                com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser();
                                com.github.javaparser.ast.CompilationUnit cu = parser.parse(javaFile).getResult()
                                        .orElse(null);
                                if (cu != null) {
                                    String filePackageName = cu.getPackageDeclaration()
                                            .map(pd -> pd.getNameAsString())
                                            .orElse("");

                                    // ラムダ式の代わりに通常のループを使用
                                    List<com.github.javaparser.ast.body.ClassOrInterfaceDeclaration> classDecls = cu
                                            .findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);

                                    for (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl : classDecls) {
                                        String fileClassName = classDecl.getNameAsString();
                                        String fileFqn = filePackageName.isEmpty()
                                                ? fileClassName
                                                : filePackageName + "." + fileClassName;
                                        if (fileFqn.equals(fqn)) {
                                            foundPath = relativePath;
                                            break;
                                        }
                                    }

                                    if (foundPath != null) {
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                // パースエラーは無視して次のファイルへ
                            }
                        }
                    } catch (Exception e) {
                        // 相対パス取得エラーは無視
                    }
                }

                // ファイルが見つからない場合は、予測パスを使用
                if (foundPath != null) {
                    filePaths.put(fqn, foundPath);
                } else if (!possiblePaths.isEmpty()) {
                    // 最初の可能なパスを使用（実際のファイルが存在するかは後で確認）
                    filePaths.put(fqn, possiblePaths.get(0));
                }
            }
        } catch (Exception e) {
            // エラーが発生した場合、予測パスのみを使用
            for (ClassEntity classEntity : classes) {
                String fqn = classEntity.getFullQualifiedName();
                if (fqn == null || fqn.isEmpty()) {
                    continue;
                }

                int lastDotIndex = fqn.lastIndexOf('.');
                String packageName = lastDotIndex >= 0 ? fqn.substring(0, lastDotIndex) : "";
                String className = lastDotIndex >= 0 ? fqn.substring(lastDotIndex + 1) : fqn;
                String packagePath = packageName.replace('.', '/');

                String filePath;
                if (packagePath.isEmpty()) {
                    filePath = "src/main/java/" + className + ".java";
                } else {
                    filePath = "src/main/java/" + packagePath + "/" + className + ".java";
                }

                filePaths.put(fqn, filePath);
            }
        }

        return filePaths;
    }
}
