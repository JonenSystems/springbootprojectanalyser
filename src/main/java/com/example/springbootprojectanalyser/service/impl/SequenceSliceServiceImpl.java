package com.example.springbootprojectanalyser.service.impl;

import com.example.springbootprojectanalyser.model.dto.*;
import com.example.springbootprojectanalyser.service.SequenceSliceService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

/**
 * シーケンス図作成用JSON生成サービス実装
 */
@Service
public class SequenceSliceServiceImpl implements SequenceSliceService {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_NODES = 200;

    @Override
    public SequenceInputDto generateSequenceInput(
        ClassDiagramDto classDiagram,
        EndpointDto endpoint,
        String projectRootPath) {
        if (classDiagram == null) {
            throw new IllegalArgumentException("クラス図情報がありません");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("エンドポイント情報がありません");
        }
        if (projectRootPath == null || projectRootPath.isEmpty()) {
            throw new IllegalArgumentException("プロジェクトパスがありません");
        }

        Map<String, String> classFilePaths = classDiagram.classFilePaths() != null
            ? classDiagram.classFilePaths()
            : Collections.emptyMap();
        ClassMetaIndex metaIndex = buildClassMetaIndex(projectRootPath, classFilePaths);

        ClassMeta controllerMeta = metaIndex.findBySimpleName(endpoint.className())
            .orElseThrow(() -> new IllegalArgumentException("コントローラクラスが見つかりません: " + endpoint.className()));

        EndpointIndex endpointIndex = buildEndpointIndex(metaIndex);
        HandlerInfo handlerInfo = endpointIndex.resolveHandler(endpoint.httpMethodName(), endpoint.uri())
            .orElseGet(() -> new HandlerInfo(controllerMeta.fqn(), "unknown"));

        String handlerDisplay = handlerInfo.classFqn() + "#" + handlerInfo.methodName();

        List<FlowDto> flows = new ArrayList<>();
        List<BranchHintDto> branchHints = new ArrayList<>();
        Map<String, ParticipantDto> participants = new LinkedHashMap<>();

        // doc/sequenceDiagram.md のルールに合わせ、クラス図に含まれる全クラスをノード定義に含める
        // （実際にフローが到達しないクラスも participant 宣言だけ行う）
        if (classDiagram.targetClasses() != null) {
            for (ClassInfoDto classInfo : classDiagram.targetClasses()) {
                if (classInfo == null || classInfo.fullQualifiedName() == null || classInfo.fullQualifiedName().isEmpty()) {
                    continue;
                }
                metaIndex.findByFqn(classInfo.fullQualifiedName())
                    .ifPresent(meta -> addParticipant(participants, buildParticipantFromMeta(meta, metaIndex)));
            }
        }

        addParticipant(participants, buildUserParticipant());
        addParticipant(participants, buildClassParticipant(controllerMeta));
        flows.add(new FlowDto(
            "call",
            "User",
            controllerMeta.simpleName(),
            endpoint.httpMethodName() + " " + endpoint.uri(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        ));

        Deque<MethodRef> queue = new ArrayDeque<>();
        queue.add(new MethodRef(controllerMeta, handlerInfo.methodName(), 0));
        Set<String> visited = new LinkedHashSet<>();

        while (!queue.isEmpty() && visited.size() < MAX_NODES) {
            MethodRef ref = queue.poll();
            String visitKey = ref.meta().fqn() + "#" + ref.methodName();
            if (!visited.add(visitKey)) {
                continue;
            }
            if (ref.depth() > MAX_DEPTH) {
                continue;
            }
            Optional<MethodDeclaration> methodOpt = ref.meta().findMethod(ref.methodName());
            if (methodOpt.isEmpty()) {
                continue;
            }
            MethodDeclaration methodDecl = methodOpt.get();

            String location = ref.meta().fqn() + "#" + ref.methodName();
            String currentParticipantId = resolveParticipantId(ref.meta(), metaIndex);
            for (IfStmt ifStmt : methodDecl.findAll(IfStmt.class)) {
                String conditionText = ifStmt.getCondition().toString();
                List<FlowDto> thenSteps = buildFlowStepsFromNode(
                    ifStmt.getThenStmt(),
                    ref.meta(),
                    metaIndex,
                    participants
                );
                List<FlowDto> elseSteps = ifStmt.getElseStmt()
                    .map(elseStmt -> buildFlowStepsFromNode(elseStmt, ref.meta(), metaIndex, participants))
                    .orElseGet(ArrayList::new);
                branchHints.add(new BranchHintDto(location, conditionText, "condition-only"));
                flows.add(new FlowDto(
                    "alt_hint",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    location,
                    conditionText,
                    null,
                    null,
                    thenSteps,
                    elseSteps,
                    null
                ));
            }

            if (isControllerLayer(ref.meta().layer())) {
                for (ReturnStmt returnStmt : methodDecl.findAll(ReturnStmt.class)) {
                    returnStmt.getExpression()
                        .flatMap(expr -> expr.toStringLiteralExpr().map(StringLiteralExpr::asString))
                        .ifPresent(template -> {
                            addParticipant(participants, buildViewParticipant(template));
                            flows.add(new FlowDto(
                                "return_view",
                                currentParticipantId,
                                "view",
                                null,
                                null,
                                null,
                                null,
                                null,
                                template,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                            ));
                        });
                }
            }

            Map<String, String> parameterTypes = extractParameterTypes(methodDecl);
            List<MethodCallExpr> calls = collectMethodCallsInSourceOrder(methodDecl);
            for (MethodCallExpr callExpr : calls) {
                if (isInsideIfStmt(callExpr)) {
                    // alt_hintに含めるため通常フローには含めない
                    continue;
                }
                String callName = callExpr.getNameAsString();

                if (isSessionCall(callExpr, ref.meta(), parameterTypes)) {
                    String key = extractStringLiteralArg(callExpr).orElse(null);
                    addParticipant(participants, buildHttpSessionParticipant());
                    flows.add(new FlowDto(
                        "session",
                        currentParticipantId,
                        "HttpSession",
                        null,
                        callName,
                        key,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                    ));
                    continue;
                }

                Optional<ClassMeta> targetMetaOpt = resolveTargetClass(callExpr, ref.meta(), metaIndex);
                if (targetMetaOpt.isEmpty()) {
                    if (isRepositoryLayer(ref.meta().layer())) {
                        Optional<DbFlowInfo> dbFlowInfo = resolveDbFlow(
                            callExpr,
                            ref.meta(),
                            metaIndex,
                            classFilePaths,
                            projectRootPath);
                        if (dbFlowInfo.isPresent()) {
                            addParticipant(participants, buildDbParticipant());
                            DbFlowInfo info = dbFlowInfo.get();
                            flows.add(new FlowDto(
                                "db",
                                currentParticipantId,
                                "DB",
                                null,
                                null,
                                null,
                                info.op(),
                                info.query(),
                                null,
                                null,
                                null,
                                null,
                                info.note(),
                                null,
                                null,
                                null
                            ));
                        }
                    }
                    continue;
                }
                ClassMeta targetMeta = targetMetaOpt.get();
                ParticipantDto participant = buildParticipantFromMeta(targetMeta, metaIndex);
                addParticipant(participants, participant);

                    flows.add(new FlowDto(
                    "call",
                    currentParticipantId,
                    participant.id(),
                        formatCallLabel(callExpr),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ));

                if (isRepositoryLayer(targetMeta.layer())) {
                    Optional<DbFlowInfo> dbFlowInfo = resolveDbFlow(
                        callExpr,
                        targetMeta,
                        metaIndex,
                        classFilePaths,
                        projectRootPath);
                    if (dbFlowInfo.isPresent()) {
                        addParticipant(participants, buildDbParticipant());
                        DbFlowInfo info = dbFlowInfo.get();
                        flows.add(new FlowDto(
                            "db",
                            participant.id(),
                            "DB",
                            null,
                            null,
                            null,
                            info.op(),
                            info.query(),
                            null,
                            null,
                            null,
                            null,
                            info.note(),
                            null,
                            null,
                            null
                        ));
                    }
                }

                if (ref.depth() < MAX_DEPTH) {
                    String nextMethodName = callName;
                    String implFqn = metaIndex.resolveImplFqn(targetMeta.fqn()).orElse(targetMeta.fqn());
                    metaIndex.findByFqn(implFqn)
                        .flatMap(meta -> meta.findMethod(nextMethodName).map(m -> meta))
                        .ifPresent(meta -> queue.add(new MethodRef(meta, nextMethodName, ref.depth() + 1)));
                }
            }
        }

        // クラス図の依存関係一覧（解析済み）をシーケンス図に反映（ラベルは 依存種類コード_依存種類名、同一親子は1矢印に集約）
        appendDependencyListFlows(classDiagram.dependencyList(), participants, flows, metaIndex);

        List<String> participantOrder = buildParticipantOrder(participants);
        ProjectInfoDto project = buildProjectInfo(projectRootPath, endpoint);
        EntryPointDto entry = new EntryPointDto(endpoint.httpMethodName(), endpoint.uri(), handlerDisplay);
        EvidenceDto evidence = new EvidenceDto("facts-only", "SequenceSlice v1.0");

        List<ParticipantDto> participantList = sortParticipants(participants, participantOrder);

        return new SequenceInputDto(
            project,
            entry,
            participantOrder,
            participantList,
            flows,
            evidence,
            branchHints
        );
    }

    /**
     * 依存関係一覧（DB に保存された解析結果）をシーケンス図フローに追加する。
     * ラベルは「依存種類コード_依存種類名」。同一（親 participant, 子 participant）の行は1本の矢印にまとめ、ラベルをカンマ区切りで連結する。
     */
    private void appendDependencyListFlows(
        List<ClassDependencyListItemDto> dependencyList,
        Map<String, ParticipantDto> participants,
        List<FlowDto> flows,
        ClassMetaIndex metaIndex) {
        if (dependencyList == null || dependencyList.isEmpty()) {
            return;
        }
        // fromId -> (toId -> ラベル断片の集合（順序・重複除去）)
        Map<String, Map<String, LinkedHashSet<String>>> fromToLabelParts = new LinkedHashMap<>();
        for (ClassDependencyListItemDto item : dependencyList) {
            if (item == null) {
                continue;
            }
            String parent = item.parentClassName();
            String child = item.childClassName();
            String code = item.dependencyKindCode();
            String kindName = item.dependencyKindName();
            if (parent == null || child == null || code == null) {
                continue;
            }
            parent = parent.trim();
            child = child.trim();
            code = code.trim();
            if (parent.isEmpty() || child.isEmpty() || code.isEmpty()) {
                continue;
            }
            String fromId = resolveParticipantIdForDependencyEdge(parent, participants, metaIndex);
            String toId = resolveParticipantIdForDependencyEdge(child, participants, metaIndex);
            if (fromId == null || toId == null) {
                continue;
            }
            String namePart = kindName != null ? kindName.trim() : "";
            String labelPart = namePart.isEmpty() ? code : code + "_" + namePart;
            fromToLabelParts
                .computeIfAbsent(fromId, k -> new LinkedHashMap<>())
                .computeIfAbsent(toId, k -> new LinkedHashSet<>())
                .add(labelPart);
        }
        for (Map.Entry<String, Map<String, LinkedHashSet<String>>> fromEntry : fromToLabelParts.entrySet()) {
            String fromId = fromEntry.getKey();
            for (Map.Entry<String, LinkedHashSet<String>> toEntry : fromEntry.getValue().entrySet()) {
                String toId = toEntry.getKey();
                // 横長防止のためカンマの直後に改行（Mermaid はラベル内の <br/> で折り返し）
                String mergedLabel = String.join(",<br/>", toEntry.getValue());
                flows.add(new FlowDto(
                    "dependency",
                    fromId,
                    toId,
                    mergedLabel,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ));
            }
        }
    }

    /**
     * 依存一覧のクラス名を、シーケンス図の participant id（多くは単純クラス名／DIはIF名）に合わせる。
     */
    private String resolveParticipantIdForDependencyEdge(
        String classSimpleName,
        Map<String, ParticipantDto> participants,
        ClassMetaIndex metaIndex) {
        if (classSimpleName == null || classSimpleName.isBlank()) {
            return null;
        }
        String trimmed = classSimpleName.trim();
        if (participants.containsKey(trimmed)) {
            return trimmed;
        }
        Optional<ClassMeta> metaOpt = metaIndex.findBySimpleName(trimmed);
        if (metaOpt.isEmpty()) {
            return null;
        }
        ClassMeta meta = metaOpt.get();
        if (!meta.isInterface()) {
            Optional<String> ifaceFqnOpt = metaIndex.resolveInterfaceFqnForImpl(meta.fqn());
            if (ifaceFqnOpt.isPresent()) {
                String ifaceSimple = simpleNameFromFqn(ifaceFqnOpt.get());
                if (participants.containsKey(ifaceSimple)) {
                    return ifaceSimple;
                }
            }
        }
        return null;
    }

    private static String simpleNameFromFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return "";
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    private ProjectInfoDto buildProjectInfo(String projectRootPath, EndpointDto endpoint) {
        String name = Paths.get(projectRootPath).getFileName() != null
            ? Paths.get(projectRootPath).getFileName().toString()
            : projectRootPath;
        String analyzedFrom = endpoint.uri() + "(" + endpoint.httpMethodName() + ")";
        return new ProjectInfoDto(name, projectRootPath, LocalDateTime.now().toString(), analyzedFrom);
    }

    private List<String> buildParticipantOrder(Map<String, ParticipantDto> participants) {
        List<String> order = new ArrayList<>();
        List<String> layerOrder = List.of(
            "User", "View", "Form", "Controller", "DTO", "Session",
            "Common", "Service", "Entity", "Repository", "Storage"
        );

        Map<String, List<String>> idsByLayer = new LinkedHashMap<>();
        for (ParticipantDto participant : participants.values()) {
            String layer = participant.layer() != null ? participant.layer() : "";
            idsByLayer.computeIfAbsent(layer, key -> new ArrayList<>()).add(participant.id());
        }

        for (String layer : layerOrder) {
            List<String> ids = idsByLayer.get(layer);
            if (ids != null) {
                order.addAll(ids);
            }
        }
        for (ParticipantDto participant : participants.values()) {
            if (!order.contains(participant.id())) {
                order.add(participant.id());
            }
        }
        return order;
    }

    private List<ParticipantDto> sortParticipants(
        Map<String, ParticipantDto> participants,
        List<String> participantOrder) {
        List<ParticipantDto> sorted = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (String id : participantOrder) {
            ParticipantDto participant = participants.get(id);
            if (participant != null && added.add(id)) {
                sorted.add(participant);
            }
        }
        for (ParticipantDto participant : participants.values()) {
            if (participant != null && added.add(participant.id())) {
                sorted.add(participant);
            }
        }
        return sorted;
    }

    private Optional<DbFlowInfo> resolveDbFlow(
        MethodCallExpr callExpr,
        ClassMeta repositoryMeta,
        ClassMetaIndex metaIndex,
        Map<String, String> classFilePaths,
        String projectRootPath) {
        String name = callExpr.getNameAsString();
        if (!name.equals("createQuery") && !name.equals("createNativeQuery") && !name.equals("createNamedQuery")) {
            return Optional.empty();
        }
        String op = name.equals("createNativeQuery") ? "SQL" : "JPQL";
        String query = extractStringLiteralArg(callExpr).orElse(null);
        String dbProduct = inferDbProductLabel(projectRootPath);
        String defaultSchema = defaultSchemaForProduct(dbProduct);

        Optional<ClassMeta> entityMeta = resolveEntityMetaForRepository(
            repositoryMeta,
            metaIndex,
            classFilePaths,
            projectRootPath);

        String tableLogical = null;
        String schema = null;

        if (entityMeta.isPresent()) {
            ClassMeta em = entityMeta.get();
            tableLogical = em.tableName() != null && !em.tableName().isBlank()
                ? em.tableName()
                : em.simpleName();
            schema = defaultSchema;
        } else if (query != null && !query.isBlank()) {
            if ("SQL".equals(op)) {
                tableLogical = extractSqlFromTable(query).orElse(null);
            } else {
                Optional<String> entityOrAlias = extractJpqlFromRoot(query);
                if (entityOrAlias.isPresent()) {
                    String root = entityOrAlias.get();
                    Optional<ClassMeta> fromMeta = metaIndex.findBySimpleName(root);
                    if (fromMeta.isPresent()) {
                        ClassMeta em = fromMeta.get();
                        tableLogical = em.tableName() != null && !em.tableName().isBlank()
                            ? em.tableName()
                            : em.simpleName();
                    } else {
                        tableLogical = root;
                    }
                }
            }
            schema = defaultSchema;
        }

        DbNoteDto note = new DbNoteDto(dbProduct, schema, tableLogical);
        return Optional.of(new DbFlowInfo(op, query, note));
    }

    /**
     * application.properties / application.yml から JDBC URL を読み、DB 製品名（表示用）を推定する。
     */
    private String inferDbProductLabel(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            return "Relational DB";
        }
        String text = readSpringDatasourceConfigText(projectRootPath);
        if (text == null || text.isBlank()) {
            return "Relational DB";
        }
        String lower = text.toLowerCase();
        if (lower.contains("jdbc:h2")) {
            return "H2 Database";
        }
        if (lower.contains("jdbc:postgresql")) {
            return "PostgreSQL";
        }
        if (lower.contains("jdbc:mysql")) {
            return "MySQL";
        }
        if (lower.contains("jdbc:mariadb")) {
            return "MariaDB";
        }
        if (lower.contains("jdbc:sqlserver")) {
            return "SQL Server";
        }
        if (lower.contains("jdbc:oracle")) {
            return "Oracle Database";
        }
        return "Relational DB";
    }

    /**
     * 解析対象プロジェクトの設定ファイルから、データソース URL が含まれるテキストを読む。
     */
    private String readSpringDatasourceConfigText(String projectRootPath) {
        Path base = Paths.get(projectRootPath).resolve("src/main/resources");
        Path[] candidates = new Path[] {
            base.resolve("application.properties"),
            base.resolve("application.yml"),
            base.resolve("application.yaml")
        };
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 次の候補へ
            }
        }
        return null;
    }

    private String defaultSchemaForProduct(String dbProduct) {
        if (dbProduct == null) {
            return null;
        }
        if ("H2 Database".equals(dbProduct)) {
            return "PUBLIC";
        }
        return null;
    }

    /**
     * Spring Data の Repository インターフェースから第1型引数（エンティティ）を推定する。
     */
    private Optional<ClassMeta> resolveEntityMetaForRepository(
        ClassMeta repositoryMeta,
        ClassMetaIndex metaIndex,
        Map<String, String> classFilePaths,
        String projectRootPath) {
        if (repositoryMeta == null || classFilePaths == null || projectRootPath == null) {
            return Optional.empty();
        }
        String rel = classFilePaths.get(repositoryMeta.fqn());
        if (rel == null || rel.isBlank()) {
            return Optional.empty();
        }
        Path path = Paths.get(projectRootPath).resolve(rel);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            JavaParser parser = new JavaParser();
            CompilationUnit cu = parser.parse(path).getResult().orElse(null);
            if (cu == null) {
                return Optional.empty();
            }
            Optional<ClassOrInterfaceDeclaration> declOpt = cu.findFirst(ClassOrInterfaceDeclaration.class,
                d -> d.getNameAsString().equals(repositoryMeta.simpleName()));
            if (declOpt.isEmpty()) {
                return Optional.empty();
            }
            ClassOrInterfaceDeclaration decl = declOpt.get();
            Optional<String> entitySimple = extractRepositoryEntitySimpleName(decl);
            if (entitySimple.isEmpty()) {
                return Optional.empty();
            }
            return metaIndex.findBySimpleName(entitySimple.get());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> extractRepositoryEntitySimpleName(ClassOrInterfaceDeclaration decl) {
        NodeList<ClassOrInterfaceType> impls = decl.getImplementedTypes();
        for (ClassOrInterfaceType impl : impls) {
            Optional<NodeList<Type>> typeArgsOpt = impl.getTypeArguments();
            if (typeArgsOpt.isEmpty() || typeArgsOpt.get().isEmpty()) {
                continue;
            }
            String implName = impl.getNameAsString();
            if (!isSpringDataRepositoryTypeName(implName)) {
                continue;
            }
            String rawEntity = typeArgsOpt.get().get(0).asString();
            String entitySimple = normalizeTypeName(rawEntity);
            if (!entitySimple.isBlank()) {
                return Optional.of(entitySimple);
            }
        }
        return Optional.empty();
    }

    private boolean isSpringDataRepositoryTypeName(String simpleName) {
        if (simpleName == null) {
            return false;
        }
        return simpleName.equals("JpaRepository")
            || simpleName.equals("CrudRepository")
            || simpleName.equals("PagingAndSortingRepository")
            || simpleName.equals("ListCrudRepository")
            || simpleName.equals("ListPagingAndSortingRepository")
            || simpleName.equals("JpaSpecificationExecutor");
    }

    private static final Pattern JPQL_FROM_PATTERN = Pattern.compile("(?is)\\bfrom\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private Optional<String> extractJpqlFromRoot(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return Optional.empty();
        }
        Matcher m = JPQL_FROM_PATTERN.matcher(jpql);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    private static final Pattern SQL_FROM_PATTERN = Pattern.compile(
        "(?is)\\bfrom\\s+([`\"\\[]?)([A-Za-z0-9_]+)\\1");

    private Optional<String> extractSqlFromTable(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SQL_FROM_PATTERN.matcher(sql);
        if (m.find()) {
            return Optional.of(m.group(2));
        }
        return Optional.empty();
    }

    private boolean isSessionCall(
        MethodCallExpr callExpr,
        ClassMeta currentMeta,
        Map<String, String> parameterTypes) {
        String name = callExpr.getNameAsString();
        if (!name.equals("getAttribute") && !name.equals("setAttribute")) {
            return false;
        }
        Optional<String> scopeName = extractScopeName(callExpr);
        if (scopeName.isEmpty()) {
            return false;
        }
        String fieldType = currentMeta.fieldTypes().get(scopeName.get());
        if (fieldType == null) {
            fieldType = parameterTypes.get(scopeName.get());
        }
        if (fieldType == null) {
            String scope = scopeName.get();
            return "session".equalsIgnoreCase(scope) || "httpSession".equalsIgnoreCase(scope);
        }
        return fieldType.endsWith("HttpSession") || fieldType.equals("HttpSession");
    }

    private Optional<String> extractStringLiteralArg(MethodCallExpr callExpr) {
        if (callExpr.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression arg = callExpr.getArgument(0);
        return arg.toStringLiteralExpr().map(StringLiteralExpr::asString);
    }

    private Optional<ClassMeta> resolveTargetClass(MethodCallExpr callExpr, ClassMeta currentMeta, ClassMetaIndex metaIndex) {
        Optional<String> scopeName = extractScopeName(callExpr);
        if (scopeName.isEmpty()) {
            return Optional.empty();
        }

        String typeName = null;
        String scope = scopeName.get();
        if (currentMeta.fieldTypes().containsKey(scope)) {
            typeName = currentMeta.fieldTypes().get(scope);
        } else if (metaIndex.simpleNameExists(scope)) {
            typeName = scope;
        }

        if (typeName == null) {
            return Optional.empty();
        }
        String resolvedFqn = metaIndex.resolveFqn(typeName).orElse(null);
        if (resolvedFqn == null) {
            return Optional.empty();
        }
        return metaIndex.findByFqn(resolvedFqn);
    }

    private Optional<String> extractScopeName(MethodCallExpr callExpr) {
        return callExpr.getScope()
            .flatMap(scope -> {
                if (scope.isNameExpr()) {
                    return Optional.of(scope.asNameExpr().getNameAsString());
                }
                if (scope.isFieldAccessExpr()) {
                    FieldAccessExpr fieldAccess = scope.asFieldAccessExpr();
                    return Optional.of(fieldAccess.getNameAsString());
                }
                if (scope.isObjectCreationExpr()) {
                    ObjectCreationExpr creationExpr = scope.asObjectCreationExpr();
                    return Optional.of(creationExpr.getType().getNameAsString());
                }
                return Optional.empty();
            });
    }

    private ParticipantDto buildParticipantFromMeta(ClassMeta meta, ClassMetaIndex metaIndex) {
        Optional<String> interfaceFqn = meta.isInterface()
            ? Optional.of(meta.fqn())
            : metaIndex.resolveInterfaceFqnForImpl(meta.fqn());
        if (interfaceFqn.isPresent()) {
            String interfaceSimple = metaIndex.simpleNameOf(interfaceFqn.get());
            String implFqn = meta.isInterface()
                ? metaIndex.resolveImplFqn(interfaceFqn.get()).orElse(null)
                : meta.fqn();
            String implSimple = implFqn != null ? metaIndex.simpleNameOf(implFqn) : interfaceSimple + "Impl";
            return new ParticipantDto(
                interfaceSimple,
                "di_pair",
                meta.layer(),
                meta.layer() + "<br/>" + interfaceSimple + "<br/>" + implSimple,
                null,
                interfaceFqn.get(),
                implFqn,
                null
            );
        }
        return buildClassParticipant(meta);
    }

    private ParticipantDto buildClassParticipant(ClassMeta meta) {
        TableInfoDto table = meta.tableName() != null
            ? new TableInfoDto(meta.tableName(), null, null)
            : null;
        return new ParticipantDto(
            meta.simpleName(),
            "class",
            meta.layer(),
            meta.layer() + "<br/>" + meta.simpleName(),
            meta.fqn(),
            null,
            null,
            table
        );
    }

    private ParticipantDto buildUserParticipant() {
        return new ParticipantDto("User", "actor", "User", "ユーザー", null, null, null, null);
    }

    private ParticipantDto buildViewParticipant(String template) {
        return new ParticipantDto("view", "view", "View", "View<br/>" + template, null, null, null, null);
    }

    private ParticipantDto buildHttpSessionParticipant() {
        return new ParticipantDto("HttpSession", "session", "Session", "HttpSession", null, null, null, null);
    }

    private ParticipantDto buildDbParticipant() {
        return new ParticipantDto("DB", "db", "Storage", "記憶媒体", null, null, null, null);
    }

    private String formatCallLabel(MethodCallExpr callExpr) {
        String name = callExpr.getNameAsString();
        if (callExpr.getArguments().isEmpty()) {
            return name + "()";
        }
        List<String> args = new ArrayList<>();
        for (Expression arg : callExpr.getArguments()) {
            args.add(arg.toString());
        }
        return name + "(" + String.join(", ", args) + ")";
    }

    private boolean isInsideIfStmt(MethodCallExpr callExpr) {
        Node current = callExpr.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof IfStmt) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private void addParticipant(Map<String, ParticipantDto> participants, ParticipantDto participant) {
        if (participant == null || participant.id() == null) {
            return;
        }
        participants.putIfAbsent(participant.id(), participant);
    }

    private String resolveParticipantId(ClassMeta meta, ClassMetaIndex metaIndex) {
        return buildParticipantFromMeta(meta, metaIndex).id();
    }

    private ClassMetaIndex buildClassMetaIndex(String projectRootPath, Map<String, String> classFilePaths) {
        Map<String, ClassMeta> byFqn = new HashMap<>();
        Map<String, ClassMeta> bySimpleName = new HashMap<>();
        Map<String, String> interfaceToImpl = new HashMap<>();

        for (Map.Entry<String, String> entry : classFilePaths.entrySet()) {
            String relPath = entry.getValue();
            Path path = Paths.get(projectRootPath).resolve(relPath);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                JavaParser parser = new JavaParser();
                CompilationUnit cu = parser.parse(path).getResult().orElse(null);
                if (cu == null) {
                    continue;
                }
                String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
                List<ClassOrInterfaceDeclaration> decls = cu.findAll(ClassOrInterfaceDeclaration.class);
                for (ClassOrInterfaceDeclaration decl : decls) {
                    String className = decl.getNameAsString();
                    String classFqn = packageName.isEmpty() ? className : packageName + "." + className;
                    Map<String, String> fieldTypes = new HashMap<>();
                    for (FieldDeclaration field : decl.getFields()) {
                        String typeName = normalizeTypeName(field.getElementType().asString());
                        field.getVariables().forEach(var -> fieldTypes.put(var.getNameAsString(), typeName));
                    }
                    Map<String, List<MethodDeclaration>> methods = new HashMap<>();
                    for (MethodDeclaration method : decl.getMethods()) {
                        methods.computeIfAbsent(method.getNameAsString(), key -> new ArrayList<>()).add(method);
                    }
                    List<String> annotations = new ArrayList<>();
                    for (AnnotationExpr annotationExpr : decl.getAnnotations()) {
                        annotations.add(annotationExpr.getNameAsString());
                    }
                    List<String> requestMappingPaths = extractClassRequestMappings(decl);
                    ClassMeta meta = new ClassMeta(
                        classFqn,
                        className,
                        decl.isInterface(),
                        annotations,
                        fieldTypes,
                        methods,
                        detectLayer(classFqn, annotations),
                        extractTableName(decl).orElse(null),
                        requestMappingPaths
                    );
                    byFqn.put(classFqn, meta);
                    bySimpleName.putIfAbsent(className, meta);

                    if (!decl.isInterface()) {
                        decl.getImplementedTypes().forEach(implType -> {
                            String interfaceName = normalizeTypeName(implType.getNameAsString());
                            String interfaceFqn = resolveFqn(interfaceName, byFqn);
                            if (interfaceFqn != null) {
                                interfaceToImpl.put(interfaceFqn, classFqn);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // パースエラーは無視
            }
        }

        return new ClassMetaIndex(byFqn, bySimpleName, interfaceToImpl);
    }

    private EndpointIndex buildEndpointIndex(ClassMetaIndex metaIndex) {
        Map<String, HandlerInfo> index = new HashMap<>();
        for (ClassMeta meta : metaIndex.all()) {
            if (!isControllerLayer(meta.layer())) {
                continue;
            }
            List<String> classPaths = meta.requestMappingPaths().isEmpty()
                ? List.of("")
                : meta.requestMappingPaths();
            for (MethodDeclaration method : meta.methodsByName().values().stream()
                .flatMap(List::stream).toList()) {
                List<MappingInfo> mappings = extractMethodMappings(method);
                if (mappings.isEmpty()) {
                    continue;
                }
                for (MappingInfo mapping : mappings) {
                    for (String classPath : classPaths) {
                        String fullPath = joinPaths(classPath, mapping.path());
                        String key = mapping.httpMethod() + " " + fullPath;
                        index.putIfAbsent(key, new HandlerInfo(meta.fqn(), method.getNameAsString()));
                    }
                }
            }
        }
        return new EndpointIndex(index);
    }

    private List<String> extractClassRequestMappings(ClassOrInterfaceDeclaration decl) {
        for (AnnotationExpr annotation : decl.getAnnotations()) {
            if ("RequestMapping".equals(annotation.getNameAsString())) {
                return extractPathsFromAnnotation(annotation);
            }
        }
        return Collections.emptyList();
    }

    private List<MappingInfo> extractMethodMappings(MethodDeclaration method) {
        List<MappingInfo> results = new ArrayList<>();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("GetMapping".equals(name)) {
                extractPathsFromAnnotation(annotation).forEach(path -> results.add(new MappingInfo("GET", path)));
            } else if ("PostMapping".equals(name)) {
                extractPathsFromAnnotation(annotation).forEach(path -> results.add(new MappingInfo("POST", path)));
            } else if ("PutMapping".equals(name)) {
                extractPathsFromAnnotation(annotation).forEach(path -> results.add(new MappingInfo("PUT", path)));
            } else if ("DeleteMapping".equals(name)) {
                extractPathsFromAnnotation(annotation).forEach(path -> results.add(new MappingInfo("DELETE", path)));
            } else if ("PatchMapping".equals(name)) {
                extractPathsFromAnnotation(annotation).forEach(path -> results.add(new MappingInfo("PATCH", path)));
            } else if ("RequestMapping".equals(name)) {
                List<String> paths = extractPathsFromAnnotation(annotation);
                List<String> methods = extractHttpMethods(annotation);
                if (methods.isEmpty()) {
                    methods = List.of("GET");
                }
                for (String httpMethod : methods) {
                    for (String path : paths) {
                        results.add(new MappingInfo(httpMethod, path));
                    }
                }
            }
        }
        return results;
    }

    private List<String> extractHttpMethods(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (!pair.getNameAsString().equals("method")) {
                continue;
            }
            Expression value = pair.getValue();
            if (value.isFieldAccessExpr()) {
                result.add(value.asFieldAccessExpr().getNameAsString());
            } else if (value.isArrayInitializerExpr()) {
                ArrayInitializerExpr array = value.asArrayInitializerExpr();
                for (Expression expr : array.getValues()) {
                    if (expr.isFieldAccessExpr()) {
                        result.add(expr.asFieldAccessExpr().getNameAsString());
                    }
                }
            }
        }
        return result;
    }

    private List<String> extractPathsFromAnnotation(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return extractPathsFromExpression(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return extractPathsFromExpression(pair.getValue());
                }
            }
        }
        return List.of("");
    }

    private List<String> extractPathsFromExpression(Expression value) {
        if (value.isStringLiteralExpr()) {
            return List.of(value.asStringLiteralExpr().asString());
        }
        if (value.isArrayInitializerExpr()) {
            List<String> paths = new ArrayList<>();
            for (Expression expr : value.asArrayInitializerExpr().getValues()) {
                if (expr.isStringLiteralExpr()) {
                    paths.add(expr.asStringLiteralExpr().asString());
                }
            }
            return paths;
        }
        return List.of("");
    }

    private String joinPaths(String base, String path) {
        String left = base == null ? "" : base.trim();
        String right = path == null ? "" : path.trim();
        if (left.isEmpty()) {
            left = "";
        }
        if (right.isEmpty()) {
            right = "";
        }
        String joined = (left + "/" + right).replaceAll("//+", "/");
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    private boolean isControllerLayer(String layer) {
        return "Controller".equals(layer);
    }

    private boolean isRepositoryLayer(String layer) {
        return "Repository".equals(layer);
    }

    private String detectLayer(String fqn, List<String> annotations) {
        if (annotations.contains("Controller") || annotations.contains("RestController")) {
            return "Controller";
        }
        if (annotations.contains("Service")) {
            return "Service";
        }
        if (annotations.contains("Repository")) {
            return "Repository";
        }
        if (annotations.contains("Entity")) {
            return "Entity";
        }
        if (fqn.contains(".controller.")) {
            return "Controller";
        }
        if (fqn.contains(".service.")) {
            return "Service";
        }
        if (fqn.contains(".repository.")) {
            return "Repository";
        }
        if (fqn.contains(".model.form.")) {
            return "Form";
        }
        if (fqn.contains(".model.dto.")) {
            return "DTO";
        }
        if (fqn.contains(".model.entity.")) {
            return "Entity";
        }
        if (fqn.contains(".common.")) {
            return "Common";
        }
        return "Common";
    }

    private Optional<String> extractTableName(ClassOrInterfaceDeclaration decl) {
        for (AnnotationExpr annotation : decl.getAnnotations()) {
            if (!annotation.getNameAsString().equals("Table")) {
                continue;
            }
            if (annotation.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if (pair.getNameAsString().equals("name") && pair.getValue().isStringLiteralExpr()) {
                        return Optional.of(pair.getValue().asStringLiteralExpr().asString());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String normalizeTypeName(String rawType) {
        String type = rawType;
        int genericIndex = type.indexOf('<');
        if (genericIndex >= 0) {
            type = type.substring(0, genericIndex);
        }
        type = type.replace("[]", "");
        return type.trim();
    }

    private String resolveFqn(String typeName, Map<String, ClassMeta> byFqn) {
        if (typeName.contains(".")) {
            return typeName;
        }
        for (String fqn : byFqn.keySet()) {
            if (fqn.endsWith("." + typeName) || fqn.equals(typeName)) {
                return fqn;
            }
        }
        return null;
    }

    private static final class ClassMetaIndex {
        private final Map<String, ClassMeta> byFqn;
        private final Map<String, ClassMeta> bySimpleName;
        private final Map<String, String> interfaceToImpl;
        private final Map<String, String> implToInterface;

        private ClassMetaIndex(
            Map<String, ClassMeta> byFqn,
            Map<String, ClassMeta> bySimpleName,
            Map<String, String> interfaceToImpl) {
            this.byFqn = byFqn;
            this.bySimpleName = bySimpleName;
            this.interfaceToImpl = interfaceToImpl;
            Map<String, String> reverse = new HashMap<>();
            for (Map.Entry<String, String> entry : interfaceToImpl.entrySet()) {
                reverse.put(entry.getValue(), entry.getKey());
            }
            this.implToInterface = reverse;
        }

        private Optional<ClassMeta> findByFqn(String fqn) {
            return Optional.ofNullable(byFqn.get(fqn));
        }

        private Optional<ClassMeta> findBySimpleName(String simpleName) {
            return Optional.ofNullable(bySimpleName.get(simpleName));
        }

        private boolean simpleNameExists(String simpleName) {
            return bySimpleName.containsKey(simpleName);
        }

        private Optional<String> resolveFqn(String typeName) {
            if (typeName == null) {
                return Optional.empty();
            }
            if (typeName.contains(".")) {
                return Optional.of(typeName);
            }
            return byFqn.keySet().stream()
                .filter(fqn -> fqn.endsWith("." + typeName) || fqn.equals(typeName))
                .findFirst();
        }

        private Optional<String> resolveImplFqn(String interfaceFqn) {
            return Optional.ofNullable(interfaceToImpl.get(interfaceFqn));
        }

        private Optional<String> resolveInterfaceFqnForImpl(String implFqn) {
            return Optional.ofNullable(implToInterface.get(implFqn));
        }

        private String simpleNameOf(String fqn) {
            int lastDot = fqn.lastIndexOf('.');
            return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        }

        private List<ClassMeta> all() {
            return new ArrayList<>(byFqn.values());
        }
    }

    private record ClassMeta(
        String fqn,
        String simpleName,
        boolean isInterface,
        List<String> annotations,
        Map<String, String> fieldTypes,
        Map<String, List<MethodDeclaration>> methodsByName,
        String layer,
        String tableName,
        List<String> requestMappingPaths
    ) {
        private Optional<MethodDeclaration> findMethod(String name) {
            List<MethodDeclaration> list = methodsByName.get(name);
            if (list == null || list.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(list.get(0));
        }
    }

    private record HandlerInfo(String classFqn, String methodName) {
    }

    private static final class EndpointIndex {
        private final Map<String, HandlerInfo> index;

        private EndpointIndex(Map<String, HandlerInfo> index) {
            this.index = index;
        }

        private Optional<HandlerInfo> resolveHandler(String httpMethod, String path) {
            String method = httpMethod != null ? httpMethod : "";
            String normalized = normalizePath(path);
            String key = method + " " + normalized;
            if (index.containsKey(key)) {
                return Optional.of(index.get(key));
            }
            return Optional.empty();
        }

        private String normalizePath(String path) {
            String value = path == null ? "" : path.trim();
            if (value.isEmpty()) {
                return "/";
            }
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            if (value.length() > 1 && value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        }
    }

    private record MappingInfo(String httpMethod, String path) {
    }

    private record MethodRef(ClassMeta meta, String methodName, int depth) {
    }

    private record DbFlowInfo(String op, String query, DbNoteDto note) {
    }

    private Map<String, String> extractParameterTypes(MethodDeclaration methodDecl) {
        Map<String, String> types = new HashMap<>();
        methodDecl.getParameters().forEach(param -> {
            String type = normalizeTypeName(param.getType().asString());
            types.put(param.getNameAsString(), type);
        });
        return types;
    }

    /**
     * 同一メソッド内の {@link MethodCallExpr} を、ソース上の出現順（行・列の昇順）に並べる。
     * {@code findAll} の列挙順には依存しない（P0）。
     */
    private List<MethodCallExpr> collectMethodCallsInSourceOrder(MethodDeclaration methodDecl) {
        List<MethodCallExpr> calls = methodDecl.findAll(MethodCallExpr.class);
        calls.sort(methodCallSourceOrderComparator());
        return calls;
    }

    private static Comparator<MethodCallExpr> methodCallSourceOrderComparator() {
        return Comparator
            .comparingInt((MethodCallExpr e) -> e.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE))
            .thenComparingInt(e -> e.getBegin().map(p -> p.column).orElse(0));
    }

    private List<MethodCallExpr> sortMethodCallsBySourceOrder(List<MethodCallExpr> calls) {
        List<MethodCallExpr> copy = new ArrayList<>(calls);
        copy.sort(methodCallSourceOrderComparator());
        return copy;
    }

    private List<FlowDto> buildFlowStepsFromNode(
        Node node,
        ClassMeta currentMeta,
        ClassMetaIndex metaIndex,
        Map<String, ParticipantDto> participants) {
        List<FlowDto> steps = new ArrayList<>();
        String fromId = resolveParticipantId(currentMeta, metaIndex);
        for (MethodCallExpr callExpr : sortMethodCallsBySourceOrder(node.findAll(MethodCallExpr.class))) {
            Optional<ClassMeta> targetMetaOpt = resolveTargetClass(callExpr, currentMeta, metaIndex);
            if (targetMetaOpt.isEmpty()) {
                continue;
            }
            ClassMeta targetMeta = targetMetaOpt.get();
            ParticipantDto participant = buildParticipantFromMeta(targetMeta, metaIndex);
            addParticipant(participants, participant);
            steps.add(new FlowDto(
                "call",
                fromId,
                participant.id(),
                formatCallLabel(callExpr),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            ));
        }
        return steps;
    }
}
