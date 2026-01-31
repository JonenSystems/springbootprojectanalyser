package com.example.springbootprojectanalyser.service.impl;

import com.example.springbootprojectanalyser.model.dto.AnalysisExecutionDto;
import com.example.springbootprojectanalyser.model.dto.AnalysisResultDto;
import com.example.springbootprojectanalyser.model.dto.PackageSummaryDto;
import com.example.springbootprojectanalyser.model.entity.*;
import com.example.springbootprojectanalyser.repository.*;
import com.example.springbootprojectanalyser.service.ClassDependencyAnalysisService;
import com.example.springbootprojectanalyser.util.SymbolSolverFactory;
import com.example.springbootprojectanalyser.util.TypeResolver;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * クラス依存関係解析サービス実装クラス
 */
@Service
public class ClassDependencyAnalysisServiceImpl implements ClassDependencyAnalysisService {

    private final ProjectRepository projectRepository;
    private final PackageInfoRepository packageInfoRepository;
    private final ClassEntityRepository classEntityRepository;
    private final ClassDependencyRepository classDependencyRepository;
    private final DependencyKindRepository dependencyKindRepository;
    private final MemberRepository memberRepository;
    private final MemberTypeRepository memberTypeRepository;
    private final AnnotationRepository annotationRepository;
    private final AnnotationAttributeRepository annotationAttributeRepository;

    public ClassDependencyAnalysisServiceImpl(
            ProjectRepository projectRepository,
            PackageInfoRepository packageInfoRepository,
            ClassEntityRepository classEntityRepository,
            ClassDependencyRepository classDependencyRepository,
            DependencyKindRepository dependencyKindRepository,
            MemberRepository memberRepository,
            MemberTypeRepository memberTypeRepository,
            AnnotationRepository annotationRepository,
            AnnotationAttributeRepository annotationAttributeRepository) {
        this.projectRepository = projectRepository;
        this.packageInfoRepository = packageInfoRepository;
        this.classEntityRepository = classEntityRepository;
        this.classDependencyRepository = classDependencyRepository;
        this.dependencyKindRepository = dependencyKindRepository;
        this.memberRepository = memberRepository;
        this.memberTypeRepository = memberTypeRepository;
        this.annotationRepository = annotationRepository;
        this.annotationAttributeRepository = annotationAttributeRepository;
    }

    @Override
    @Transactional
    public AnalysisResultDto executeAnalysis(AnalysisExecutionDto executionDto) {
        String targetProjectPath = executionDto.targetProjectPath();
        // TODO: targetPackagePatternは将来の実装で使用予定
        @SuppressWarnings("unused")
        String targetPackagePattern = executionDto.targetPackagePattern();

        // パスの検証
        Path projectRoot = Paths.get(targetProjectPath);
        if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("指定されたパスが存在しないか、ディレクトリではありません: " + targetProjectPath);
        }

        // 既存データを削除
        annotationAttributeRepository.deleteAll();
        annotationRepository.deleteAll();
        memberRepository.deleteAll();
        classDependencyRepository.deleteAll();
        classEntityRepository.deleteAll();
        packageInfoRepository.deleteAll();
        projectRepository.deleteAll();

        // プロジェクトを作成
        Project project = new Project(targetProjectPath);
        project = projectRepository.save(project);

        // Javaファイルを収集
        List<Path> javaFiles = collectJavaFiles(projectRoot);
        System.out.println("Found " + javaFiles.size() + " Java files");

        if (javaFiles.isEmpty()) {
            throw new IllegalArgumentException("Javaファイルが見つかりませんでした: " + targetProjectPath);
        }

        // パッケージとクラスを解析・登録
        Map<String, PackageInfo> packageMap = new HashMap<>();
        Map<String, ClassEntity> classMap = new HashMap<>();

        int parsedCount = 0;
        int errorCount = 0;
        for (Path javaFile : javaFiles) {
            try {
                parseAndRegister(javaFile, project, projectRoot, packageMap, classMap);
                parsedCount++;
            } catch (Exception e) {
                // パースエラーはログに記録してスキップ
                errorCount++;
                System.err.println("Failed to parse: " + javaFile + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Parsed: " + parsedCount + ", Errors: " + errorCount);
        System.out.println("Packages: " + packageMap.size() + ", Classes: " + classMap.size());

        // メンバー情報を抽出・保存
        parseMembers(javaFiles, projectRoot, classMap);

        // Symbol Solverを生成
        JavaSymbolSolver symbolSolver = SymbolSolverFactory.createSymbolSolver(projectRoot);

        // 依存関係を解析
        parseDependencies(javaFiles, projectRoot, classMap, symbolSolver);

        // オートコンフィグ解析（pom.xmlとMETA-INF/spring.factories）
        parseAutoConfiguration(projectRoot, project, classMap);

        // ビルド依存解析（pom.xml/build.gradle）
        parseBuildDependencies(projectRoot, project, classMap);

        return getAnalysisResult(targetProjectPath);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisResultDto getAnalysisResult(String projectPath) {
        Project project = projectRepository.findByRootPath(projectPath)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectPath));

        List<PackageInfo> packages = packageInfoRepository.findByProject(project);

        List<PackageSummaryDto> packageSummaries = packages.stream()
                .map(pkg -> createPackageSummary(pkg))
                .sorted(Comparator.comparing(PackageSummaryDto::packageName))
                .collect(Collectors.toList());

        return new AnalysisResultDto(projectPath, packageSummaries);
    }

    private List<Path> collectJavaFiles(Path root) {
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target")) // targetディレクトリを除外
                    .filter(p -> !p.toString().contains(".git")) // .gitディレクトリを除外
                    .forEach(javaFiles::add);
        } catch (Exception e) {
            throw new RuntimeException("Failed to collect Java files from: " + root, e);
        }
        return javaFiles;
    }

    private void parseAndRegister(Path javaFile, Project project, Path projectRoot,
            Map<String, PackageInfo> packageMap,
            Map<String, ClassEntity> classMap) throws Exception {
        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(javaFile).getResult().orElseThrow();

        // パッケージ情報を取得・登録
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        // パッケージ名が空の場合は"<default>"として扱う
        String displayPackageName = packageName.isEmpty() ? "<default>" : packageName;

        PackageInfo packageInfo = packageMap.computeIfAbsent(displayPackageName, name -> {
            String[] parts = name.equals("<default>") ? new String[] { "<default>" } : name.split("\\.");
            String simpleName = parts[parts.length - 1];
            PackageInfo pkg = new PackageInfo(project, name, simpleName);
            return packageInfoRepository.save(pkg);
        });

        // クラスを登録
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = classDecl.getNameAsString();
            String fullQualifiedName = packageName.isEmpty()
                    ? className
                    : packageName + "." + className;

            // パッケージ名が空の場合でも、表示用パッケージ名を使用してマップに登録
            String mapKey = packageName.isEmpty()
                    ? "<default>." + className
                    : fullQualifiedName;

            if (!classMap.containsKey(mapKey)) {
                ClassEntity classEntity = new ClassEntity(project, packageInfo, fullQualifiedName, className);
                classEntity = classEntityRepository.save(classEntity);
                classMap.put(mapKey, classEntity);
            }
        });
    }

    private void parseDependencies(List<Path> javaFiles, Path projectRoot,
            Map<String, ClassEntity> classMap,
            JavaSymbolSolver symbolSolver) {
        // JavaParserの設定でSymbol Solverを有効化
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setSymbolResolver(symbolSolver);

        for (Path javaFile : javaFiles) {
            try {
                JavaParser parser = new JavaParser(parserConfiguration);
                CompilationUnit cu = parser.parse(javaFile).getResult().orElseThrow();

                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");

                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                    String className = classDecl.getNameAsString();
                    String sourceFqn = packageName.isEmpty()
                            ? className
                            : packageName + "." + className;

                    // パッケージ名が空の場合のマップキー
                    String mapKey = packageName.isEmpty()
                            ? "<default>." + className
                            : sourceFqn;

                    ClassEntity sourceClass = classMap.get(mapKey);
                    if (sourceClass == null) {
                        return;
                    }

                    // 001_001: 継承（extends）
                    classDecl.getExtendedTypes().forEach(extendedType -> {
                        String targetFqn = TypeResolver.resolveFullyQualifiedName(extendedType, cu, packageName,
                                classMap, symbolSolver);
                        if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                            saveDependency(sourceClass, sourceFqn, targetFqn, "001_001", classMap);
                        }
                    });

                    // 001_002: 実装（implements）
                    classDecl.getImplementedTypes().forEach(implType -> {
                        String targetFqn = TypeResolver.resolveFullyQualifiedName(implType, cu, packageName, classMap,
                                symbolSolver);
                        if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                            saveDependency(sourceClass, sourceFqn, targetFqn, "001_002", classMap);
                        }
                    });

                    // 001_004: 例外型依存（throws句とcatch節）
                    // throws句の例外型依存
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getThrownExceptions().forEach(exceptionType -> {
                            String targetFqn = TypeResolver.resolveFullyQualifiedName(exceptionType, cu, packageName,
                                    classMap, symbolSolver);
                            if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                saveDependency(sourceClass, sourceFqn, targetFqn, "001_004", classMap);
                            }
                        });
                    });

                    // catch節の例外型依存
                    classDecl.findAll(CatchClause.class).forEach(catchClause -> {
                        com.github.javaparser.ast.body.Parameter param = catchClause.getParameter();
                        if (param != null) {
                            Type exceptionType = param.getType();
                            if (exceptionType != null) {
                                String targetFqn = TypeResolver.resolveFullyQualifiedName(exceptionType, cu,
                                        packageName, classMap, symbolSolver);
                                if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                    saveDependency(sourceClass, sourceFqn, targetFqn, "001_004", classMap);
                                }
                            }
                        }
                    });

                    // 001_006: 戻り値型依存（このクラス内のメソッドのみ）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        Type returnType = method.getType();
                        if (returnType != null && !returnType.isVoidType() && !returnType.isPrimitiveType()) {
                            String targetFqn = TypeResolver.resolveFullyQualifiedName(returnType, cu, packageName,
                                    classMap, symbolSolver);
                            if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                saveDependency(sourceClass, sourceFqn, targetFqn, "001_006", classMap);
                            }
                        }
                    });

                    // 001_003, 001_010: ジェネリクス型参照、集合保持（メソッドの戻り値型から抽出）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        Type returnType = method.getType();
                        if (returnType != null && !returnType.isVoidType() && !returnType.isPrimitiveType()) {
                            extractGenericTypes(returnType, cu, packageName, classMap, symbolSolver)
                                    .forEach(genericType -> {
                                        if (genericType != null && !genericType.isEmpty()
                                                && !isPrimitiveOrBasicType(genericType)) {
                                            saveDependency(sourceClass, sourceFqn, genericType, "001_003", classMap);
                                        }
                                    });
                        }
                    });

                    // 001_007: 引数型依存（このクラス内のメソッドのみ）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getParameters().forEach(param -> {
                            Type paramType = param.getType();
                            if (paramType != null && !paramType.isPrimitiveType()) {
                                String targetFqn = TypeResolver.resolveFullyQualifiedName(paramType, cu, packageName,
                                        classMap, symbolSolver);
                                if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                    saveDependency(sourceClass, sourceFqn, targetFqn, "001_007", classMap);
                                }
                            }
                        });
                    });

                    // 001_003, 001_010: ジェネリクス型参照、集合保持（メソッドの引数型から抽出）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getParameters().forEach(param -> {
                            Type paramType = param.getType();
                            if (paramType != null && !paramType.isPrimitiveType()) {
                                extractGenericTypes(paramType, cu, packageName, classMap, symbolSolver)
                                        .forEach(genericType -> {
                                            if (genericType != null && !genericType.isEmpty()
                                                    && !isPrimitiveOrBasicType(genericType)) {
                                                saveDependency(sourceClass, sourceFqn, genericType, "001_003",
                                                        classMap);
                                            }
                                        });
                            }
                        });
                    });

                    // 001_005: メソッド呼び出し（このクラス内のメソッド呼び出しのみ）
                    classDecl.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        Optional<Expression> scope = methodCall.getScope();
                        if (scope.isPresent()) {
                            Expression scopeExpr = scope.get();
                            String targetClassName = extractClassNameFromScope(scopeExpr, classDecl, cu, packageName,
                                    classMap);
                            if (targetClassName != null && !targetClassName.isEmpty()
                                    && !isPrimitiveOrBasicType(targetClassName)
                                    && !targetClassName.equals(className)) { // 自分自身の呼び出しは除外
                                saveDependency(sourceClass, sourceFqn, targetClassName, "001_005", classMap);
                            }
                        }
                    });

                    // 001_008: 静的メソッド依存（このクラス内の静的メソッド呼び出しのみ）
                    classDecl.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        Optional<Expression> scope = methodCall.getScope();
                        if (scope.isPresent()) {
                            Expression scopeExpr = scope.get();
                            String staticClassName = extractStaticClassNameFromScope(scopeExpr, classDecl, cu,
                                    packageName, classMap);
                            if (staticClassName != null && !staticClassName.isEmpty()
                                    && !isPrimitiveOrBasicType(staticClassName)
                                    && !staticClassName.equals(className)) { // 自分自身の呼び出しは除外
                                saveDependency(sourceClass, sourceFqn, staticClassName, "001_008", classMap);
                            }
                        }
                    });

                    // 001_011: 定数参照（このクラス内の定数参照のみ）
                    // メソッド呼び出しのスコープとして使用されているFieldAccessExprを除外するため、
                    // まず全てのMethodCallExprのスコープを収集
                    Set<Expression> methodCallScopes = new HashSet<>();
                    classDecl.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        methodCall.getScope().ifPresent(methodCallScopes::add);
                    });

                    classDecl.findAll(FieldAccessExpr.class).forEach(fieldAccess -> {
                        // メソッド呼び出しのスコープとして使用されている場合は除外
                        if (methodCallScopes.contains(fieldAccess)) {
                            return;
                        }

                        String constantClassName = extractConstantClassName(fieldAccess, classDecl, cu, packageName,
                                classMap);
                        if (constantClassName != null && !constantClassName.isEmpty()
                                && !isPrimitiveOrBasicType(constantClassName)
                                && !constantClassName.equals(className)) { // 自分自身の定数参照は除外
                            saveDependency(sourceClass, sourceFqn, constantClassName, "001_011", classMap);
                        }
                    });

                    // 001_009: コンポジション（保持）（このクラス内のフィールドのみ）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        Type fieldType = field.getCommonType();
                        if (fieldType != null && !fieldType.isPrimitiveType()) {
                            String targetFqn = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName,
                                    classMap, symbolSolver);
                            if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                saveDependency(sourceClass, sourceFqn, targetFqn, "001_009", classMap);
                            }
                        }
                    });

                    // 001_003, 001_010: ジェネリクス型参照、集合保持（このクラス内のフィールドのみ）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        Type fieldType = field.getCommonType();
                        extractGenericTypes(fieldType, cu, packageName, classMap, symbolSolver).forEach(genericType -> {
                            if (genericType != null && !genericType.isEmpty() && !isPrimitiveOrBasicType(genericType)) {
                                saveDependency(sourceClass, sourceFqn, genericType, "001_003", classMap);
                            }
                        });
                    });

                    // 002_001: SetterDI（@Autowiredかつset*命名のメソッド）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Autowired") && method.getNameAsString().startsWith("set")) {
                            method.getParameters().forEach(param -> {
                                Type paramType = param.getType();
                                if (paramType != null && !paramType.isPrimitiveType()) {
                                    String targetFqn = TypeResolver.resolveFullyQualifiedName(paramType, cu,
                                            packageName, classMap, symbolSolver);
                                    if (targetFqn != null && !targetFqn.isEmpty()
                                            && !isPrimitiveOrBasicType(targetFqn)) {
                                        saveDependency(sourceClass, sourceFqn, targetFqn, "002_001", classMap);
                                    }
                                }
                            });
                        }
                    });

                    // 002_002: @Bean提供（@Configuration内の@Beanメソッド）
                    if (hasAnnotation(classDecl, "Configuration")) {
                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            if (hasAnnotation(method, "Bean")) {
                                Type returnType = method.getType();
                                if (returnType != null && !returnType.isVoidType() && !returnType.isPrimitiveType()) {
                                    String targetFqn = TypeResolver.resolveFullyQualifiedName(returnType, cu,
                                            packageName, classMap, symbolSolver);
                                    if (targetFqn != null && !targetFqn.isEmpty()
                                            && !isPrimitiveOrBasicType(targetFqn)) {
                                        saveDependency(sourceClass, sourceFqn, targetFqn, "002_002", classMap);
                                    }
                                }
                            }
                        });
                    }

                    // 002_003: コンストラクタDI（コンストラクタの引数型と@Autowired）
                    classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
                        // @Autowiredがあるか、または単一コンストラクタの場合はDIとみなす
                        if (hasAnnotation(constructor, "Autowired") || classDecl.getConstructors().size() == 1) {
                            constructor.getParameters().forEach(param -> {
                                Type paramType = param.getType();
                                if (paramType != null && !paramType.isPrimitiveType()) {
                                    String targetFqn = TypeResolver.resolveFullyQualifiedName(paramType, cu,
                                            packageName, classMap, symbolSolver);
                                    if (targetFqn != null && !targetFqn.isEmpty()
                                            && !isPrimitiveOrBasicType(targetFqn)) {
                                        saveDependency(sourceClass, sourceFqn, targetFqn, "002_003", classMap);
                                    }
                                }
                            });
                        }
                    });

                    // 002_004: フィールドDI（フィールドに対する@Autowired）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        if (hasAnnotation(field, "Autowired")) {
                            Type fieldType = field.getCommonType();
                            if (fieldType != null && !fieldType.isPrimitiveType()) {
                                String targetFqn = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName,
                                        classMap, symbolSolver);
                                if (targetFqn != null && !targetFqn.isEmpty() && !isPrimitiveOrBasicType(targetFqn)) {
                                    saveDependency(sourceClass, sourceFqn, targetFqn, "002_004", classMap);
                                }
                            }
                        }
                    });

                    // 002_005: コントローラ定義（@RestController注釈）
                    if (hasAnnotation(classDecl, "RestController")) {
                        // コントローラ自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "002_005", classMap);
                    }

                    // 002_006: サービス層定義（@Service注釈）
                    if (hasAnnotation(classDecl, "Service")) {
                        // サービス層自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "002_006", classMap);
                    }

                    // 002_007: リポジトリ層定義（@Repositoryまたは*Repository命名/JpaRepository継承）
                    boolean isRepository = hasAnnotation(classDecl, "Repository")
                            || className.endsWith("Repository")
                            || classDecl.getExtendedTypes().stream().anyMatch(type -> {
                                String typeName = TypeResolver.resolveFullyQualifiedName(type, cu, packageName,
                                        classMap, symbolSolver);
                                return typeName != null && typeName.contains("JpaRepository");
                            });
                    if (isRepository) {
                        // リポジトリ層自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "002_007", classMap);
                    }

                    // 003_001: JPAリポジトリ（JpaRepositoryを継承しているクラス/インタフェース）
                    classDecl.getExtendedTypes().forEach(extendedType -> {
                        String typeName = TypeResolver.resolveFullyQualifiedName(extendedType, cu, packageName,
                                classMap, symbolSolver);
                        if (typeName != null
                                && (typeName.equals("org.springframework.data.jpa.repository.JpaRepository")
                                        || typeName.contains("JpaRepository"))) {
                            saveDependency(sourceClass, sourceFqn, typeName, "003_001", classMap);
                        }
                    });

                    // 003_002: JPAエンティティ（@Entity注釈を持つクラス）
                    if (hasAnnotation(classDecl, "Entity")) {
                        // エンティティ自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "003_002", classMap);
                    }

                    // 003_003: クエリメソッド（Repositoryインタフェース内のメソッド名規約/@Query）
                    // Repositoryインタフェースかどうかをチェック（JpaRepositoryを継承しているか、*Repository命名）
                    boolean isRepositoryInterface = classDecl.isInterface() && (isRepository
                            || classDecl.getExtendedTypes().stream().anyMatch(type -> {
                                String typeName = TypeResolver.resolveFullyQualifiedName(type, cu, packageName,
                                        classMap, symbolSolver);
                                return typeName != null && typeName.contains("Repository");
                            }));
                    if (isRepositoryInterface) {
                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            String methodName = method.getNameAsString();
                            // メソッド名がfindBy、find、get、count、exists等で始まる場合、または@Queryアノテーションがある場合
                            if (hasAnnotation(method, "Query")
                                    || methodName.startsWith("findBy")
                                    || methodName.startsWith("find")
                                    || methodName.startsWith("get")
                                    || methodName.startsWith("count")
                                    || methodName.startsWith("exists")
                                    || methodName.startsWith("delete")
                                    || methodName.startsWith("save")) {
                                // クエリメソッド自体を依存関係として記録（依存先はメソッド名）
                                saveDependency(sourceClass, sourceFqn, methodName, "003_003", classMap);
                            }
                        });
                    }

                    // 003_004: DTO（DTOパッケージ/純データクラス（record/POJO））
                    // パッケージ名に"dto"が含まれる場合、またはクラス名が"Dto"で終わる場合
                    boolean isDtoPackage = packageName.toLowerCase().contains("dto");
                    boolean isDtoClass = className.endsWith("Dto") || className.endsWith("DTO");
                    if (isDtoPackage || isDtoClass) {
                        // DTO自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "003_004", classMap);
                    }

                    // 003_005: マッパー（@Mapper/@Mapping注釈を持つクラス/インタフェース）
                    if (hasAnnotation(classDecl, "Mapper")) {
                        // マッパー自体を依存関係として記録（依存先は自身のクラス名）
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "003_005", classMap);

                        // マッパーメソッドの引数と戻り値から変換関係を抽出
                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            if (hasAnnotation(method, "Mapping")) {
                                // 引数型から変換元を抽出
                                method.getParameters().forEach(param -> {
                                    Type paramType = param.getType();
                                    if (paramType != null && !paramType.isPrimitiveType()) {
                                        String sourceType = TypeResolver.resolveFullyQualifiedName(paramType, cu,
                                                packageName, classMap, symbolSolver);
                                        if (sourceType != null && !sourceType.isEmpty()
                                                && !isPrimitiveOrBasicType(sourceType)) {
                                            saveDependency(sourceClass, sourceFqn, sourceType, "003_005", classMap);
                                        }
                                    }
                                });

                                // 戻り値型から変換先を抽出
                                Type returnType = method.getType();
                                if (returnType != null && !returnType.isVoidType() && !returnType.isPrimitiveType()) {
                                    String targetType = TypeResolver.resolveFullyQualifiedName(returnType, cu,
                                            packageName, classMap, symbolSolver);
                                    if (targetType != null && !targetType.isEmpty()
                                            && !isPrimitiveOrBasicType(targetType)) {
                                        saveDependency(sourceClass, sourceFqn, targetType, "003_005", classMap);
                                    }
                                }
                            }
                        });
                    }

                    // 004_001: @Value注入（@Value注釈から設定プレースホルダ${...}を抽出）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        if (hasAnnotation(field, "Value")) {
                            AnnotationExpr valueAnnotation = field.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("Value") || name.endsWith(".Value")
                                                || name.equals("org.springframework.beans.factory.annotation.Value");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (valueAnnotation != null) {
                                String value = extractAnnotationValue(valueAnnotation, "value");
                                if (value != null && value.startsWith("${") && value.endsWith("}")) {
                                    // ${...}内のキーを抽出
                                    String key = value.substring(2, value.length() - 1);
                                    // デフォルト値の処理（例: ${app.name:default}）
                                    if (key.contains(":")) {
                                        key = key.substring(0, key.indexOf(":"));
                                    }
                                    saveDependency(sourceClass, sourceFqn, key, "004_001", classMap);
                                }
                            }
                        }
                    });

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Value")) {
                            AnnotationExpr valueAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("Value") || name.endsWith(".Value")
                                                || name.equals("org.springframework.beans.factory.annotation.Value");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (valueAnnotation != null) {
                                String value = extractAnnotationValue(valueAnnotation, "value");
                                if (value != null && value.startsWith("${") && value.endsWith("}")) {
                                    String key = value.substring(2, value.length() - 1);
                                    if (key.contains(":")) {
                                        key = key.substring(0, key.indexOf(":"));
                                    }
                                    saveDependency(sourceClass, sourceFqn, key, "004_001", classMap);
                                }
                            }
                        }
                    });

                    // 004_002: 構成プロパティ（@ConfigurationPropertiesから構成プロパティを抽出）
                    if (hasAnnotation(classDecl, "ConfigurationProperties")) {
                        String prefix = extractAnnotationAttributeValue(classDecl, "ConfigurationProperties", "prefix");
                        if (prefix != null && !prefix.isEmpty()) {
                            // 構成プロパティ自体を依存関係として記録（依存先はprefix）
                            saveDependency(sourceClass, sourceFqn, prefix, "004_002", classMap);
                        } else {
                            // prefixが指定されていない場合、クラス名から推測（例: AppProperties → app）
                            String defaultPrefix = className.replaceAll("([A-Z])", "-$1").toLowerCase()
                                    .replaceFirst("^-", "");
                            saveDependency(sourceClass, sourceFqn, defaultPrefix, "004_002", classMap);
                        }
                    }

                    // 004_003: プロファイル条件（@Profile/@Conditionalからプロファイル条件を抽出）
                    if (hasAnnotation(classDecl, "Profile")) {
                        String[] profiles = extractAnnotationAttributeArrayValue(classDecl, "Profile", "value");
                        if (profiles != null && profiles.length > 0) {
                            for (String profile : profiles) {
                                saveDependency(sourceClass, sourceFqn, "profile:" + profile, "004_003", classMap);
                            }
                        }
                    }

                    if (hasAnnotation(classDecl, "Conditional")) {
                        // @ConditionalOnClass, @ConditionalOnProperty等も考慮
                        String[] conditions = extractAnnotationAttributeArrayValue(classDecl, "Conditional", "value");
                        if (conditions != null && conditions.length > 0) {
                            for (String condition : conditions) {
                                saveDependency(sourceClass, sourceFqn, "condition:" + condition, "004_003", classMap);
                            }
                        }
                    }

                    // 004_004: オートコンフィグ（@AutoConfiguration注釈を持つクラスを検出）
                    // 注: pom.xmlとMETA-INF/spring.factoriesの解析はparseAutoConfigurationメソッドで実装
                    if (hasAnnotation(classDecl, "AutoConfiguration")) {
                        saveDependency(sourceClass, sourceFqn, sourceFqn, "004_004", classMap);
                    }

                    // 004_005: ビルド依存
                    // 注: pom.xmlやbuild.gradleの解析はparseBuildDependenciesメソッドで実装

                    // 005_001: アプリイベント購読（@EventListenerでイベント発生元へ依存）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "EventListener")) {
                            // メソッドの引数からイベント型を抽出
                            method.getParameters().forEach(param -> {
                                Type paramType = param.getType();
                                if (paramType != null && !paramType.isPrimitiveType()) {
                                    String eventType = TypeResolver.resolveFullyQualifiedName(paramType, cu,
                                            packageName, classMap, symbolSolver);
                                    if (eventType != null && !eventType.isEmpty()
                                            && !isPrimitiveOrBasicType(eventType)) {
                                        saveDependency(sourceClass, sourceFqn, eventType, "005_001", classMap);
                                    }
                                }
                            });

                            // @EventListener注釈のclasses属性からもイベント型を抽出
                            AnnotationExpr eventListenerAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("EventListener") || name.endsWith(".EventListener")
                                                || name.equals("org.springframework.context.event.EventListener");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (eventListenerAnnotation != null) {
                                // classes属性からイベント型を抽出（配列値）
                                // 注: 複雑な型配列の解析は簡略化して実装
                            }
                        }
                    });

                    // 005_002: HTTPクライアント（WebClient/RestTemplate/@FeignClient）
                    // WebClient、RestTemplateの使用を検出
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        Type fieldType = field.getCommonType();
                        if (fieldType != null) {
                            String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName,
                                    classMap, symbolSolver);
                            if (typeName != null) {
                                if (typeName.contains("WebClient")) {
                                    saveDependency(sourceClass, sourceFqn, "WebClient", "005_002", classMap);
                                } else if (typeName.contains("RestTemplate")) {
                                    saveDependency(sourceClass, sourceFqn, "RestTemplate", "005_002", classMap);
                                }
                            }
                        }
                    });

                    // @FeignClient注釈を持つインタフェースを検出
                    if (classDecl.isInterface() && hasAnnotation(classDecl, "FeignClient")) {
                        String serviceName = extractAnnotationAttributeValue(classDecl, "FeignClient", "name");
                        String serviceUrl = extractAnnotationAttributeValue(classDecl, "FeignClient", "url");
                        String targetIdentifier = serviceName != null && !serviceName.isEmpty()
                                ? serviceName
                                : (serviceUrl != null && !serviceUrl.isEmpty() ? serviceUrl
                                        : "FeignClient:" + className);
                        saveDependency(sourceClass, sourceFqn, targetIdentifier, "005_002", classMap);
                    }

                    // 005_003: メッセージング（@KafkaListener/@RabbitListener等）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        // @KafkaListener
                        if (hasAnnotation(method, "KafkaListener")) {
                            AnnotationExpr kafkaListenerAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("KafkaListener") || name.endsWith(".KafkaListener")
                                                || name.equals("org.springframework.kafka.annotation.KafkaListener");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (kafkaListenerAnnotation != null) {
                                String topics = extractAnnotationValue(kafkaListenerAnnotation, "topics");
                                String topicPattern = extractAnnotationValue(kafkaListenerAnnotation, "topicPattern");
                                if (topics != null && !topics.isEmpty()) {
                                    saveDependency(sourceClass, sourceFqn, "kafka:topic:" + topics, "005_003",
                                            classMap);
                                } else if (topicPattern != null && !topicPattern.isEmpty()) {
                                    saveDependency(sourceClass, sourceFqn, "kafka:pattern:" + topicPattern, "005_003",
                                            classMap);
                                } else {
                                    saveDependency(sourceClass, sourceFqn, "kafka:listener:" + method.getNameAsString(),
                                            "005_003", classMap);
                                }
                            }
                        }

                        // @RabbitListener
                        if (hasAnnotation(method, "RabbitListener")) {
                            AnnotationExpr rabbitListenerAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("RabbitListener") || name.endsWith(".RabbitListener")
                                                || name.equals(
                                                        "org.springframework.amqp.rabbit.annotation.RabbitListener");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (rabbitListenerAnnotation != null) {
                                String queues = extractAnnotationValue(rabbitListenerAnnotation, "queues");
                                String queue = extractAnnotationValue(rabbitListenerAnnotation, "queue");
                                if (queues != null && !queues.isEmpty()) {
                                    saveDependency(sourceClass, sourceFqn, "rabbitmq:queue:" + queues, "005_003",
                                            classMap);
                                } else if (queue != null && !queue.isEmpty()) {
                                    saveDependency(sourceClass, sourceFqn, "rabbitmq:queue:" + queue, "005_003",
                                            classMap);
                                } else {
                                    saveDependency(sourceClass, sourceFqn,
                                            "rabbitmq:listener:" + method.getNameAsString(), "005_003", classMap);
                                }
                            }
                        }
                    });

                    // 006_001: トランザクション（@TransactionalでTx境界に依存）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Transactional")) {
                            AnnotationExpr transactionalAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("Transactional") || name.endsWith(".Transactional")
                                                || name.equals(
                                                        "org.springframework.transaction.annotation.Transactional")
                                                || name.equals("jakarta.transaction.Transactional");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (transactionalAnnotation != null) {
                                String propagation = extractAnnotationValue(transactionalAnnotation, "propagation");
                                String isolation = extractAnnotationValue(transactionalAnnotation, "isolation");
                                String timeout = extractAnnotationValue(transactionalAnnotation, "timeout");
                                String readOnly = extractAnnotationValue(transactionalAnnotation, "readOnly");

                                StringBuilder targetIdentifier = new StringBuilder("Transaction");
                                if (propagation != null && !propagation.isEmpty()) {
                                    targetIdentifier.append(":propagation=").append(propagation);
                                }
                                if (isolation != null && !isolation.isEmpty()) {
                                    targetIdentifier.append(":isolation=").append(isolation);
                                }
                                if (timeout != null && !timeout.isEmpty()) {
                                    targetIdentifier.append(":timeout=").append(timeout);
                                }
                                if (readOnly != null && !readOnly.isEmpty()) {
                                    targetIdentifier.append(":readOnly=").append(readOnly);
                                }
                                saveDependency(sourceClass, sourceFqn, targetIdentifier.toString(), "006_001",
                                        classMap);
                            }
                        }
                    });

                    // クラスレベルでの@Transactionalも検出
                    if (hasAnnotation(classDecl, "Transactional")) {
                        AnnotationExpr transactionalAnnotation = classDecl.getAnnotations().stream()
                                .filter(annotation -> {
                                    String name = annotation.getNameAsString();
                                    return name.equals("Transactional") || name.endsWith(".Transactional")
                                            || name.equals("org.springframework.transaction.annotation.Transactional")
                                            || name.equals("jakarta.transaction.Transactional");
                                })
                                .findFirst()
                                .orElse(null);

                        if (transactionalAnnotation != null) {
                            saveDependency(sourceClass, sourceFqn, "Transaction:class-level", "006_001", classMap);
                        }
                    }

                    // 006_002: 横断的関心事（@Aspect/ポイントカットでの横断依存）
                    if (hasAnnotation(classDecl, "Aspect")) {
                        // @Aspectクラスを検出
                        saveDependency(sourceClass, sourceFqn, "Aspect:" + className, "006_002", classMap);

                        // ポイントカット式を抽出
                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            // @Around, @Before, @After, @AfterReturning, @AfterThrowing等
                            if (hasAnnotation(method, "Around") || hasAnnotation(method, "Before")
                                    || hasAnnotation(method, "After") || hasAnnotation(method, "AfterReturning")
                                    || hasAnnotation(method, "AfterThrowing") || hasAnnotation(method, "Pointcut")) {

                                AnnotationExpr adviceAnnotation = method.getAnnotations().stream()
                                        .filter(annotation -> {
                                            String name = annotation.getNameAsString();
                                            return name.equals("Around") || name.equals("Before")
                                                    || name.equals("After")
                                                    || name.equals("AfterReturning") || name.equals("AfterThrowing")
                                                    || name.equals("Pointcut")
                                                    || name.endsWith(".Around") || name.endsWith(".Before")
                                                    || name.endsWith(".After") || name.endsWith(".AfterReturning")
                                                    || name.endsWith(".AfterThrowing") || name.endsWith(".Pointcut");
                                        })
                                        .findFirst()
                                        .orElse(null);

                                if (adviceAnnotation != null) {
                                    String pointcut = extractAnnotationValue(adviceAnnotation, "value");
                                    if (pointcut == null || pointcut.isEmpty()) {
                                        pointcut = extractAnnotationValue(adviceAnnotation, "pointcut");
                                    }
                                    if (pointcut != null && !pointcut.isEmpty()) {
                                        saveDependency(sourceClass, sourceFqn, "Pointcut:" + pointcut, "006_002",
                                                classMap);
                                    } else {
                                        saveDependency(sourceClass, sourceFqn, "Advice:" + method.getNameAsString(),
                                                "006_002", classMap);
                                    }
                                }
                            }
                        });
                    }

                    // 006_003: ログ/メトリクス（Micrometerや@Timed等の観測依存）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        // @Timed注釈
                        if (hasAnnotation(method, "Timed")) {
                            AnnotationExpr timedAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("Timed") || name.endsWith(".Timed")
                                                || name.equals("io.micrometer.core.annotation.Timed");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (timedAnnotation != null) {
                                String value = extractAnnotationValue(timedAnnotation, "value");
                                String name = extractAnnotationValue(timedAnnotation, "name");
                                String metricName = value != null && !value.isEmpty() ? value
                                        : (name != null && !name.isEmpty() ? name : method.getNameAsString());
                                saveDependency(sourceClass, sourceFqn, "Metric:Timed:" + metricName, "006_003",
                                        classMap);
                            }
                        }

                        // @Counted注釈
                        if (hasAnnotation(method, "Counted")) {
                            AnnotationExpr countedAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("Counted") || name.endsWith(".Counted")
                                                || name.equals("io.micrometer.core.annotation.Counted");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (countedAnnotation != null) {
                                String value = extractAnnotationValue(countedAnnotation, "value");
                                String name = extractAnnotationValue(countedAnnotation, "name");
                                String metricName = value != null && !value.isEmpty() ? value
                                        : (name != null && !name.isEmpty() ? name : method.getNameAsString());
                                saveDependency(sourceClass, sourceFqn, "Metric:Counted:" + metricName, "006_003",
                                        classMap);
                            }
                        }
                    });

                    // ログライブラリの使用を検出（Logger、LoggerFactory等）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getVariables().forEach(variable -> {
                            String typeName = variable.getType().asString();
                            if (typeName.contains("Logger") || typeName.contains("Log")) {
                                if (typeName.contains("org.slf4j.Logger")
                                        || typeName.contains("org.apache.logging.log4j.Logger")
                                        || typeName.contains("java.util.logging.Logger")) {
                                    saveDependency(sourceClass, sourceFqn, "Logger:" + typeName, "006_003", classMap);
                                }
                            }
                        });
                    });

                    // 006_004: Bean Validation（@Valid/@NotNull等の制約依存）
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        // FieldDeclarationからアノテーションを取得
                        field.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            // Bean Validationの制約注釈を検出
                            if (annotationName.equals("NotNull") || annotationName.equals("NotEmpty")
                                    || annotationName.equals("NotBlank") || annotationName.equals("Size")
                                    || annotationName.equals("Min") || annotationName.equals("Max")
                                    || annotationName.equals("Email") || annotationName.equals("Pattern")
                                    || annotationName.equals("Past") || annotationName.equals("Future")
                                    || annotationName.equals("DecimalMin") || annotationName.equals("DecimalMax")
                                    || annotationName.endsWith(".NotNull") || annotationName.endsWith(".NotEmpty")
                                    || annotationName.endsWith(".NotBlank") || annotationName.endsWith(".Size")
                                    || annotationName.endsWith(".Min") || annotationName.endsWith(".Max")
                                    || annotationName.endsWith(".Email") || annotationName.endsWith(".Pattern")
                                    || annotationName.endsWith(".Past") || annotationName.endsWith(".Future")
                                    || annotationName.endsWith(".DecimalMin") || annotationName.endsWith(".DecimalMax")
                                    || annotationName.equals("jakarta.validation.constraints.NotNull")
                                    || annotationName.equals("jakarta.validation.constraints.NotEmpty")
                                    || annotationName.equals("jakarta.validation.constraints.NotBlank")
                                    || annotationName.equals("jakarta.validation.constraints.Size")
                                    || annotationName.equals("jakarta.validation.constraints.Min")
                                    || annotationName.equals("jakarta.validation.constraints.Max")
                                    || annotationName.equals("jakarta.validation.constraints.Email")
                                    || annotationName.equals("jakarta.validation.constraints.Pattern")
                                    || annotationName.equals("javax.validation.constraints.NotNull")
                                    || annotationName.equals("javax.validation.constraints.NotEmpty")
                                    || annotationName.equals("javax.validation.constraints.NotBlank")
                                    || annotationName.equals("javax.validation.constraints.Size")
                                    || annotationName.equals("javax.validation.constraints.Min")
                                    || annotationName.equals("javax.validation.constraints.Max")
                                    || annotationName.equals("javax.validation.constraints.Email")
                                    || annotationName.equals("javax.validation.constraints.Pattern")) {
                                saveDependency(sourceClass, sourceFqn, "Validation:" + annotationName, "006_004",
                                        classMap);
                            }
                        });
                    });

                    // メソッドパラメータの@ValidとBean Validation注釈を検出
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        // @Valid注釈
                        method.getParameters().forEach(parameter -> {
                            parameter.getAnnotations().forEach(annotation -> {
                                String annotationName = annotation.getNameAsString();
                                if (annotationName.equals("Valid") || annotationName.endsWith(".Valid")
                                        || annotationName.equals("jakarta.validation.Valid")
                                        || annotationName.equals("javax.validation.Valid")) {
                                    saveDependency(sourceClass, sourceFqn,
                                            "Validation:@Valid:" + parameter.getNameAsString(), "006_004", classMap);
                                }

                                // パラメータのBean Validation注釈
                                if (annotationName.equals("NotNull") || annotationName.equals("NotEmpty")
                                        || annotationName.equals("NotBlank") || annotationName.equals("Size")
                                        || annotationName.equals("Min") || annotationName.equals("Max")
                                        || annotationName.equals("Email") || annotationName.equals("Pattern")
                                        || annotationName.endsWith(".NotNull") || annotationName.endsWith(".NotEmpty")
                                        || annotationName.endsWith(".NotBlank") || annotationName.endsWith(".Size")
                                        || annotationName.endsWith(".Min") || annotationName.endsWith(".Max")
                                        || annotationName.endsWith(".Email") || annotationName.endsWith(".Pattern")
                                        || annotationName.equals("jakarta.validation.constraints.NotNull")
                                        || annotationName.equals("jakarta.validation.constraints.NotEmpty")
                                        || annotationName.equals("jakarta.validation.constraints.NotBlank")
                                        || annotationName.equals("jakarta.validation.constraints.Size")
                                        || annotationName.equals("jakarta.validation.constraints.Min")
                                        || annotationName.equals("jakarta.validation.constraints.Max")
                                        || annotationName.equals("jakarta.validation.constraints.Email")
                                        || annotationName.equals("jakarta.validation.constraints.Pattern")
                                        || annotationName.equals("javax.validation.constraints.NotNull")
                                        || annotationName.equals("javax.validation.constraints.NotEmpty")
                                        || annotationName.equals("javax.validation.constraints.NotBlank")
                                        || annotationName.equals("javax.validation.constraints.Size")
                                        || annotationName.equals("javax.validation.constraints.Min")
                                        || annotationName.equals("javax.validation.constraints.Max")
                                        || annotationName.equals("javax.validation.constraints.Email")
                                        || annotationName.equals("javax.validation.constraints.Pattern")) {
                                    saveDependency(sourceClass, sourceFqn,
                                            "Validation:" + annotationName + ":" + parameter.getNameAsString(),
                                            "006_004", classMap);
                                }
                            });
                        });
                    });

                    // 007_001: SecurityFilterChain構成（@BeanメソッドでSecurityFilterChainを返す）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Bean")) {
                            String returnType = method.getType().asString();
                            if (returnType.contains("SecurityFilterChain")) {
                                saveDependency(sourceClass, sourceFqn,
                                        "SecurityFilterChain:" + method.getNameAsString(), "007_001", classMap);
                            }
                        }
                    });

                    // 007_002: HttpSecurityルール（authorizeHttpRequests等の保護ルール）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("authorizeHttpRequests") || methodName.equals("authorizeRequests")
                                    || methodName.equals("requestMatchers") || methodName.equals("antMatchers")
                                    || methodName.equals("mvcMatchers") || methodName.equals("regexMatchers")
                                    || methodName.equals("permitAll") || methodName.equals("authenticated")
                                    || methodName.equals("hasRole") || methodName.equals("hasAnyRole")
                                    || methodName.equals("hasAuthority") || methodName.equals("hasAnyAuthority")
                                    || methodName.equals("access") || methodName.equals("denyAll")) {
                                saveDependency(sourceClass, sourceFqn, "HttpSecurity:" + methodName, "007_002",
                                        classMap);
                            }
                        });
                    });

                    // 007_003: UserDetails（UserDetails実装クラス）
                    if (classDecl.getExtendedTypes().stream()
                            .anyMatch(type -> type.getNameAsString().contains("UserDetails"))) {
                        saveDependency(sourceClass, sourceFqn, "UserDetails:implementation", "007_003", classMap);
                    }

                    // GrantedAuthority供給箇所を検出
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("getAuthorities") || methodName.equals("getRoles")
                                    || methodName.contains("GrantedAuthority")) {
                                saveDependency(sourceClass, sourceFqn, "GrantedAuthority:" + methodName, "007_003",
                                        classMap);
                            }
                        });
                    });

                    // 007_004: UserDetailsService（loadUserByUsernameメソッドを持つ実装クラス）
                    if (classDecl.getImplementedTypes().stream()
                            .anyMatch(type -> type.getNameAsString().contains("UserDetailsService"))) {
                        saveDependency(sourceClass, sourceFqn, "UserDetailsService:implementation", "007_004",
                                classMap);
                    }

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (method.getNameAsString().equals("loadUserByUsername")) {
                            saveDependency(sourceClass, sourceFqn, "UserDetailsService:loadUserByUsername", "007_004",
                                    classMap);
                        }
                    });

                    // 007_005: PasswordEncoder（Bean定義・参照箇所）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Bean")) {
                            String returnType = method.getType().asString();
                            if (returnType.contains("PasswordEncoder")) {
                                saveDependency(sourceClass, sourceFqn,
                                        "PasswordEncoder:@Bean:" + method.getNameAsString(), "007_005", classMap);
                            }
                        }
                    });

                    // PasswordEncoderの参照を検出
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getVariables().forEach(variable -> {
                            String typeName = variable.getType().asString();
                            if (typeName.contains("PasswordEncoder")) {
                                saveDependency(sourceClass, sourceFqn,
                                        "PasswordEncoder:field:" + variable.getNameAsString(), "007_005", classMap);
                            }
                        });
                    });

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getParameters().forEach(parameter -> {
                            String typeName = parameter.getType().asString();
                            if (typeName.contains("PasswordEncoder")) {
                                saveDependency(sourceClass, sourceFqn,
                                        "PasswordEncoder:parameter:" + parameter.getNameAsString(), "007_005",
                                        classMap);
                            }
                        });

                        // new BCryptPasswordEncoder()等のインスタンス生成を検出
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.contains("PasswordEncoder") || methodName.contains("BCrypt")
                                    || methodName.contains("Argon2") || methodName.contains("Pbkdf2")) {
                                saveDependency(sourceClass, sourceFqn, "PasswordEncoder:new:" + methodName, "007_005",
                                        classMap);
                            }
                        });
                    });

                    // 007_006: AuthenticationManager（authenticate呼び出し箇所）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("authenticate")) {
                                // スコープがAuthenticationManagerかどうかを確認
                                if (methodCall.getScope().isPresent()) {
                                    String scopeName = methodCall.getScope().get().toString();
                                    if (scopeName.contains("AuthenticationManager")
                                            || scopeName.contains("authenticationManager")) {
                                        saveDependency(sourceClass, sourceFqn, "AuthenticationManager:authenticate",
                                                "007_006", classMap);
                                    }
                                } else {
                                    // スコープがない場合は、フィールドやパラメータから推測
                                    saveDependency(sourceClass, sourceFqn, "AuthenticationManager:authenticate",
                                            "007_006", classMap);
                                }
                            }
                        });
                    });

                    // 007_007: AuthenticationProvider（実装/Bean登録）
                    if (classDecl.getImplementedTypes().stream()
                            .anyMatch(type -> type.getNameAsString().contains("AuthenticationProvider"))) {
                        saveDependency(sourceClass, sourceFqn, "AuthenticationProvider:implementation", "007_007",
                                classMap);
                    }

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "Bean")) {
                            String returnType = method.getType().asString();
                            if (returnType.contains("AuthenticationProvider")) {
                                saveDependency(sourceClass, sourceFqn,
                                        "AuthenticationProvider:@Bean:" + method.getNameAsString(), "007_007",
                                        classMap);
                            }
                        }
                    });

                    // 007_008: OncePerRequestFilter（継承/doFilterInternal実装）
                    if (classDecl.getExtendedTypes().stream()
                            .anyMatch(type -> type.getNameAsString().contains("OncePerRequestFilter"))) {
                        saveDependency(sourceClass, sourceFqn, "OncePerRequestFilter:extends", "007_008", classMap);
                    }

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (method.getNameAsString().equals("doFilterInternal")) {
                            saveDependency(sourceClass, sourceFqn, "OncePerRequestFilter:doFilterInternal", "007_008",
                                    classMap);
                        }
                    });

                    // 007_009: メソッドセキュリティ（@PreAuthorize/@PostAuthorize）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        if (hasAnnotation(method, "PreAuthorize") || hasAnnotation(method, "PostAuthorize")
                                || hasAnnotation(method, "Secured") || hasAnnotation(method, "RolesAllowed")) {
                            AnnotationExpr securityAnnotation = method.getAnnotations().stream()
                                    .filter(annotation -> {
                                        String name = annotation.getNameAsString();
                                        return name.equals("PreAuthorize") || name.equals("PostAuthorize")
                                                || name.equals("Secured") || name.equals("RolesAllowed")
                                                || name.endsWith(".PreAuthorize") || name.endsWith(".PostAuthorize")
                                                || name.endsWith(".Secured") || name.endsWith(".RolesAllowed");
                                    })
                                    .findFirst()
                                    .orElse(null);

                            if (securityAnnotation != null) {
                                String value = extractAnnotationValue(securityAnnotation, "value");
                                String annotationName = securityAnnotation.getNameAsString();
                                String targetIdentifier = annotationName
                                        + (value != null && !value.isEmpty() ? ":" + value : "");
                                saveDependency(sourceClass, sourceFqn, targetIdentifier, "007_009", classMap);
                            }
                        }
                    });

                    // 007_010: ロール/権限（SimpleGrantedAuthority生成）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.contains("GrantedAuthority") || methodName.contains("SimpleGrantedAuthority")
                                    || methodName.contains("ROLE_") || methodName.contains("SCOPE_")) {
                                // 引数からロール/権限名を抽出
                                if (methodCall.getArguments().size() > 0) {
                                    Expression arg = methodCall.getArguments().get(0);
                                    if (arg instanceof StringLiteralExpr) {
                                        String roleName = ((StringLiteralExpr) arg).getValue();
                                        saveDependency(sourceClass, sourceFqn, "Role:" + roleName, "007_010", classMap);
                                    } else {
                                        saveDependency(sourceClass, sourceFqn, "Role:" + methodName, "007_010",
                                                classMap);
                                    }
                                } else {
                                    saveDependency(sourceClass, sourceFqn, "Role:" + methodName, "007_010", classMap);
                                }
                            }
                        });
                    });

                    // 007_011: SecurityContext（SecurityContextHolder.getContext()呼び出し）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("getContext")) {
                                if (methodCall.getScope().isPresent()) {
                                    String scopeName = methodCall.getScope().get().toString();
                                    if (scopeName.contains("SecurityContextHolder")) {
                                        saveDependency(sourceClass, sourceFqn, "SecurityContext:getContext", "007_011",
                                                classMap);
                                    }
                                }
                            } else if (methodName.equals("getAuthentication")
                                    || methodName.equals("setAuthentication")) {
                                saveDependency(sourceClass, sourceFqn, "SecurityContext:" + methodName, "007_011",
                                        classMap);
                            }
                        });
                    });

                    // 007_012: Session管理（sessionCreationPolicy設定）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("sessionManagement") || methodName.equals("sessionCreationPolicy")) {
                                saveDependency(sourceClass, sourceFqn, "SessionManagement:" + methodName, "007_012",
                                        classMap);
                            }
                        });
                    });

                    // 007_013: トークン抽出（Authorization: Bearerヘッダ処理）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("getHeader") || methodName.equals("get")
                                    || methodName.contains("Authorization") || methodName.contains("Bearer")) {
                                // 引数に"Authorization"や"Bearer"が含まれるか確認
                                boolean hasAuthHeader = methodCall.getArguments().stream()
                                        .anyMatch(arg -> arg.toString().contains("Authorization")
                                                || arg.toString().contains("Bearer"));
                                if (hasAuthHeader || methodName.contains("Authorization")
                                        || methodName.contains("Bearer")) {
                                    saveDependency(sourceClass, sourceFqn, "TokenExtraction:" + methodName, "007_013",
                                            classMap);
                                }
                            }
                        });
                    });

                    // 007_014: 署名/検証（JWTライブラリのVerifier/Parser呼び出し）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.contains("JWT") || methodName.contains("Jws")
                                    || methodName.contains("Verifier") || methodName.contains("Parser")
                                    || methodName.contains("verify") || methodName.contains("parse")
                                    || methodName.contains("Nimbus") || methodName.contains("JwtDecoder")) {
                                saveDependency(sourceClass, sourceFqn, "JWT:" + methodName, "007_014", classMap);
                            }
                        });
                    });

                    // JWT関連の型を検出
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getVariables().forEach(variable -> {
                            String typeName = variable.getType().asString();
                            if (typeName.contains("JWT") || typeName.contains("Jws")
                                    || typeName.contains("JwtDecoder") || typeName.contains("JwtEncoder")
                                    || typeName.contains("Nimbus")) {
                                saveDependency(sourceClass, sourceFqn, "JWT:type:" + typeName, "007_014", classMap);
                            }
                        });
                    });

                    // 007_015: クレーム→権限（claimsからGrantedAuthorityへ変換）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.contains("getClaim") || methodName.contains("getClaims")
                                    || (methodName.contains("GrantedAuthority")
                                            && methodCall.getArguments().size() > 0)) {
                                // claimsから権限への変換を検出
                                saveDependency(sourceClass, sourceFqn, "ClaimToAuthority:" + methodName, "007_015",
                                        classMap);
                            }
                        });
                    });

                    // 007_016: ログイン/ログアウト（formLogin/logout設定）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("formLogin") || methodName.equals("httpBasic")
                                    || methodName.equals("logout") || methodName.equals("loginPage")
                                    || methodName.equals("loginProcessingUrl") || methodName.equals("defaultSuccessUrl")
                                    || methodName.equals("failureUrl") || methodName.equals("logoutUrl")
                                    || methodName.equals("logoutSuccessUrl")) {
                                saveDependency(sourceClass, sourceFqn, "LoginLogout:" + methodName, "007_016",
                                        classMap);
                            }
                        });
                    });

                    // 007_017: CORS/CSRF（http.cors()/http.csrf()設定）
                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("cors") || methodName.equals("csrf")
                                    || methodName.equals("corsConfigurationSource")
                                    || methodName.equals("csrfTokenRepository")
                                    || methodName.equals("disable") || methodName.equals("and")) {
                                // disable()の前後でcors()やcsrf()が呼ばれているか確認
                                if (methodName.equals("cors") || methodName.equals("csrf")) {
                                    saveDependency(sourceClass, sourceFqn, "CorsCsrf:" + methodName, "007_017",
                                            classMap);
                                } else if (methodName.equals("disable")) {
                                    // 前のメソッド呼び出しを確認（簡易実装）
                                    saveDependency(sourceClass, sourceFqn, "CorsCsrf:disable", "007_017", classMap);
                                }
                            }
                        });
                    });

                    // 008_001: Lombok（lombok.*注釈の有無を記録）
                    classDecl.getAnnotations().forEach(annotation -> {
                        String annotationName = annotation.getNameAsString();
                        if (annotationName.startsWith("lombok.") || annotationName.equals("Getter")
                                || annotationName.equals("Setter") || annotationName.equals("Data")
                                || annotationName.equals("Builder") || annotationName.equals("AllArgsConstructor")
                                || annotationName.equals("NoArgsConstructor")
                                || annotationName.equals("RequiredArgsConstructor")
                                || annotationName.equals("ToString") || annotationName.equals("EqualsAndHashCode")
                                || annotationName.equals("Slf4j") || annotationName.equals("Log")
                                || annotationName.equals("Value") || annotationName.equals("With")
                                || annotationName.endsWith(".Getter") || annotationName.endsWith(".Setter")
                                || annotationName.endsWith(".Data") || annotationName.endsWith(".Builder")
                                || annotationName.endsWith(".AllArgsConstructor")
                                || annotationName.endsWith(".NoArgsConstructor")
                                || annotationName.endsWith(".RequiredArgsConstructor")
                                || annotationName.endsWith(".ToString")
                                || annotationName.endsWith(".EqualsAndHashCode") || annotationName.endsWith(".Slf4j")
                                || annotationName.endsWith(".Log") || annotationName.endsWith(".Value")
                                || annotationName.endsWith(".With")) {
                            saveDependency(sourceClass, sourceFqn, "Lombok:" + annotationName, "008_001", classMap);
                        }
                    });

                    // フィールド、メソッド、コンストラクタのLombok注釈も検出
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.startsWith("lombok.") || annotationName.equals("Getter")
                                    || annotationName.equals("Setter") || annotationName.endsWith(".Getter")
                                    || annotationName.endsWith(".Setter")) {
                                saveDependency(sourceClass, sourceFqn, "Lombok:" + annotationName, "008_001", classMap);
                            }
                        });
                    });

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.startsWith("lombok.")) {
                                saveDependency(sourceClass, sourceFqn, "Lombok:" + annotationName, "008_001", classMap);
                            }
                        });
                    });

                    classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
                        constructor.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.startsWith("lombok.") || annotationName.equals("AllArgsConstructor")
                                    || annotationName.equals("NoArgsConstructor")
                                    || annotationName.equals("RequiredArgsConstructor")
                                    || annotationName.endsWith(".AllArgsConstructor")
                                    || annotationName.endsWith(".NoArgsConstructor")
                                    || annotationName.endsWith(".RequiredArgsConstructor")) {
                                saveDependency(sourceClass, sourceFqn, "Lombok:" + annotationName, "008_001", classMap);
                            }
                        });
                    });

                    // 008_002: Jackson（ObjectMapper利用/@Json*注釈）
                    // ObjectMapperの使用を検出
                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getVariables().forEach(variable -> {
                            String typeName = variable.getType().asString();
                            if (typeName.contains("ObjectMapper") || typeName.contains("JsonNode")
                                    || typeName.contains("ObjectReader") || typeName.contains("ObjectWriter")) {
                                saveDependency(sourceClass, sourceFqn, "Jackson:ObjectMapper:" + typeName, "008_002",
                                        classMap);
                            }
                        });
                    });

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getParameters().forEach(parameter -> {
                            String typeName = parameter.getType().asString();
                            if (typeName.contains("ObjectMapper") || typeName.contains("JsonNode")
                                    || typeName.contains("ObjectReader") || typeName.contains("ObjectWriter")) {
                                saveDependency(sourceClass, sourceFqn, "Jackson:ObjectMapper:" + typeName, "008_002",
                                        classMap);
                            }
                        });

                        // ObjectMapperのメソッド呼び出しを検出
                        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                            String methodName = methodCall.getNameAsString();
                            if (methodName.equals("readValue") || methodName.equals("writeValueAsString")
                                    || methodName.equals("writeValue") || methodName.equals("readTree")
                                    || methodName.equals("convertValue") || methodName.equals("valueToTree")
                                    || methodName.contains("Json")) {
                                if (methodCall.getScope().isPresent()) {
                                    String scopeName = methodCall.getScope().get().toString();
                                    if (scopeName.contains("ObjectMapper") || scopeName.contains("objectMapper")) {
                                        saveDependency(sourceClass, sourceFqn, "Jackson:ObjectMapper:" + methodName,
                                                "008_002", classMap);
                                    }
                                } else {
                                    saveDependency(sourceClass, sourceFqn, "Jackson:ObjectMapper:" + methodName,
                                            "008_002", classMap);
                                }
                            }
                        });
                    });

                    // @Json*注釈を検出
                    classDecl.getAnnotations().forEach(annotation -> {
                        String annotationName = annotation.getNameAsString();
                        if (annotationName.startsWith("Json") || annotationName.startsWith("JsonProperty")
                                || annotationName.equals("JsonIgnore") || annotationName.equals("JsonIgnoreProperties")
                                || annotationName.equals("JsonInclude") || annotationName.equals("JsonFormat")
                                || annotationName.equals("JsonManagedReference")
                                || annotationName.equals("JsonBackReference")
                                || annotationName.equals("JsonIdentityInfo") || annotationName.equals("JsonTypeInfo")
                                || annotationName.endsWith(".JsonIgnore")
                                || annotationName.endsWith(".JsonIgnoreProperties")
                                || annotationName.endsWith(".JsonInclude") || annotationName.endsWith(".JsonFormat")
                                || annotationName.endsWith(".JsonProperty")
                                || annotationName.endsWith(".JsonManagedReference")
                                || annotationName.endsWith(".JsonBackReference")
                                || annotationName.endsWith(".JsonIdentityInfo")
                                || annotationName.endsWith(".JsonTypeInfo")
                                || annotationName.contains("com.fasterxml.jackson")) {
                            saveDependency(sourceClass, sourceFqn, "Jackson:annotation:" + annotationName, "008_002",
                                    classMap);
                        }
                    });

                    classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                        field.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.startsWith("Json") || annotationName.startsWith("JsonProperty")
                                    || annotationName.equals("JsonIgnore")
                                    || annotationName.equals("JsonIgnoreProperties")
                                    || annotationName.equals("JsonInclude") || annotationName.equals("JsonFormat")
                                    || annotationName.equals("JsonManagedReference")
                                    || annotationName.equals("JsonBackReference")
                                    || annotationName.endsWith(".JsonIgnore")
                                    || annotationName.endsWith(".JsonIgnoreProperties")
                                    || annotationName.endsWith(".JsonInclude") || annotationName.endsWith(".JsonFormat")
                                    || annotationName.endsWith(".JsonProperty")
                                    || annotationName.endsWith(".JsonManagedReference")
                                    || annotationName.endsWith(".JsonBackReference")
                                    || annotationName.contains("com.fasterxml.jackson")) {
                                saveDependency(sourceClass, sourceFqn, "Jackson:annotation:" + annotationName,
                                        "008_002", classMap);
                            }
                        });
                    });

                    classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                        method.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.startsWith("Json") || annotationName.startsWith("JsonProperty")
                                    || annotationName.equals("JsonIgnore")
                                    || annotationName.equals("JsonIgnoreProperties")
                                    || annotationName.equals("JsonInclude") || annotationName.equals("JsonFormat")
                                    || annotationName.endsWith(".JsonIgnore")
                                    || annotationName.endsWith(".JsonIgnoreProperties")
                                    || annotationName.endsWith(".JsonInclude") || annotationName.endsWith(".JsonFormat")
                                    || annotationName.endsWith(".JsonProperty")
                                    || annotationName.contains("com.fasterxml.jackson")) {
                                saveDependency(sourceClass, sourceFqn, "Jackson:annotation:" + annotationName,
                                        "008_002", classMap);
                            }
                        });
                    });

                    // 009_001: Controller→Service（層間の正しい依存）
                    boolean isController = hasAnnotation(classDecl, "Controller")
                            || hasAnnotation(classDecl, "RestController");
                    if (isController) {
                        // Controller内のDIフィールド型がServiceであることを確認
                        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                            field.getVariables().forEach(variable -> {
                                String typeName = variable.getType().asString();
                                // Serviceクラスかどうかを確認（クラス名が*Serviceで終わる、または@Service注釈を持つ）
                                ClassEntity targetServiceClass = classMap.values().stream()
                                        .filter(ce -> {
                                            String targetClassName = ce.getFullQualifiedName();
                                            return targetClassName.equals(typeName)
                                                    || (targetClassName.endsWith("Service")
                                                            && typeName.endsWith("Service"))
                                                    || (targetClassName.contains(".") && targetClassName
                                                            .substring(targetClassName.lastIndexOf(".") + 1)
                                                            .equals(typeName));
                                        })
                                        .findFirst()
                                        .orElse(null);

                                if (targetServiceClass != null) {
                                    String targetFqn = targetServiceClass.getFullQualifiedName();
                                    // Serviceクラスかどうかを確認（既に検出したクラス情報から判断）
                                    if (targetFqn.contains("Service") || classMap.containsKey(targetFqn)) {
                                        // 型解決を使用してServiceクラスを確認
                                        String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                                variable.getType(), cu, packageName, classMap, symbolSolver);
                                        if (resolvedType != null && (resolvedType.contains("Service") ||
                                                classMap.containsKey(resolvedType))) {
                                            saveDependency(sourceClass, sourceFqn, resolvedType, "009_001", classMap);
                                        }
                                    }
                                } else if (typeName.contains("Service")) {
                                    // 型名にServiceが含まれる場合、簡易的に記録
                                    saveDependency(sourceClass, sourceFqn, typeName, "009_001", classMap);
                                }
                            });
                        });

                        // コンストラクタパラメータも確認
                        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
                            constructor.getParameters().forEach(parameter -> {
                                String typeName = parameter.getType().asString();
                                if (typeName.contains("Service")) {
                                    String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                            parameter.getType(), cu, packageName, classMap, symbolSolver);
                                    if (resolvedType != null) {
                                        saveDependency(sourceClass, sourceFqn, resolvedType, "009_001", classMap);
                                    } else {
                                        saveDependency(sourceClass, sourceFqn, typeName, "009_001", classMap);
                                    }
                                }
                            });
                        });
                    }

                    // 009_002: Service→Repository（層間の正しい依存）
                    boolean isService = hasAnnotation(classDecl, "Service");
                    if (isService) {
                        // Service内のDIフィールド型がRepositoryであることを確認
                        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                            field.getVariables().forEach(variable -> {
                                String typeName = variable.getType().asString();
                                if (typeName.contains("Repository")) {
                                    String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                            variable.getType(), cu, packageName, classMap, symbolSolver);
                                    if (resolvedType != null) {
                                        saveDependency(sourceClass, sourceFqn, resolvedType, "009_002", classMap);
                                    } else {
                                        saveDependency(sourceClass, sourceFqn, typeName, "009_002", classMap);
                                    }
                                }
                            });
                        });

                        // コンストラクタパラメータも確認
                        classDecl.findAll(ConstructorDeclaration.class).forEach(constructor -> {
                            constructor.getParameters().forEach(parameter -> {
                                String typeName = parameter.getType().asString();
                                if (typeName.contains("Repository")) {
                                    String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                            parameter.getType(), cu, packageName, classMap, symbolSolver);
                                    if (resolvedType != null) {
                                        saveDependency(sourceClass, sourceFqn, resolvedType, "009_002", classMap);
                                    } else {
                                        saveDependency(sourceClass, sourceFqn, typeName, "009_002", classMap);
                                    }
                                }
                            });
                        });
                    }

                    // 009_003: Repository→Entity（永続化対象への依存）
                    if (isRepository) {
                        // JpaRepository<T,ID>のT型を抽出
                        classDecl.getExtendedTypes().forEach(extendedType -> {
                            String extendedTypeName = extendedType.getNameAsString();
                            if (extendedTypeName.contains("Repository")) {
                                // ジェネリクス型引数を抽出
                                if (extendedType.isClassOrInterfaceType()) {
                                    extendedType.asClassOrInterfaceType().getTypeArguments()
                                            .ifPresent(typeArgs -> {
                                                if (typeArgs.size() > 0) {
                                                    Type entityType = typeArgs.get(0);
                                                    String entityTypeName = entityType.asString();
                                                    String resolvedEntityType = TypeResolver.resolveFullyQualifiedName(
                                                            entityType, cu, packageName, classMap, symbolSolver);
                                                    if (resolvedEntityType != null) {
                                                        saveDependency(sourceClass, sourceFqn, resolvedEntityType,
                                                                "009_003", classMap);
                                                    } else {
                                                        saveDependency(sourceClass, sourceFqn, entityTypeName,
                                                                "009_003", classMap);
                                                    }
                                                }
                                            });
                                }
                            }
                        });
                    }

                    // 009_004: パス/パラメータ依存（@PathVariable/@RequestParam等の契約依存）
                    if (isController) {
                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            // HTTPメソッドマッピング注釈からパスを抽出
                            if (hasAnnotation(method, "GetMapping") || hasAnnotation(method, "PostMapping")
                                    || hasAnnotation(method, "PutMapping") || hasAnnotation(method, "DeleteMapping")
                                    || hasAnnotation(method, "PatchMapping")
                                    || hasAnnotation(method, "RequestMapping")) {

                                AnnotationExpr mappingAnnotation = method.getAnnotations().stream()
                                        .filter(annotation -> {
                                            String name = annotation.getNameAsString();
                                            return name.equals("GetMapping") || name.equals("PostMapping")
                                                    || name.equals("PutMapping") || name.equals("DeleteMapping")
                                                    || name.equals("PatchMapping") || name.equals("RequestMapping")
                                                    || name.endsWith(".GetMapping") || name.endsWith(".PostMapping")
                                                    || name.endsWith(".PutMapping") || name.endsWith(".DeleteMapping")
                                                    || name.endsWith(".PatchMapping")
                                                    || name.endsWith(".RequestMapping");
                                        })
                                        .findFirst()
                                        .orElse(null);

                                if (mappingAnnotation != null) {
                                    String path = extractAnnotationValue(mappingAnnotation, "value");
                                    if (path == null || path.isEmpty()) {
                                        path = extractAnnotationValue(mappingAnnotation, "path");
                                    }
                                    if (path != null && !path.isEmpty()) {
                                        saveDependency(sourceClass, sourceFqn, "Path:" + path, "009_004", classMap);
                                    }
                                }
                            }

                            // パラメータ注釈を抽出
                            method.getParameters().forEach(parameter -> {
                                parameter.getAnnotations().forEach(paramAnnotation -> {
                                    String annotationName = paramAnnotation.getNameAsString();
                                    if (annotationName.equals("PathVariable") || annotationName.equals("RequestParam")
                                            || annotationName.equals("RequestBody")
                                            || annotationName.equals("RequestHeader")
                                            || annotationName.equals("CookieValue")
                                            || annotationName.equals("ModelAttribute")
                                            || annotationName.endsWith(".PathVariable")
                                            || annotationName.endsWith(".RequestParam")
                                            || annotationName.endsWith(".RequestBody")
                                            || annotationName.endsWith(".RequestHeader")
                                            || annotationName.endsWith(".CookieValue")
                                            || annotationName.endsWith(".ModelAttribute")) {

                                        String paramName = parameter.getNameAsString();
                                        String value = extractAnnotationValue(paramAnnotation, "value");
                                        String targetIdentifier = annotationName + ":" + paramName;
                                        if (value != null && !value.isEmpty()) {
                                            targetIdentifier += "=" + value;
                                        }
                                        saveDependency(sourceClass, sourceFqn, targetIdentifier, "009_004", classMap);
                                    }
                                });
                            });
                        });
                    }

                    // 009_006: Model→Controller（Model操作のMVC依存）
                    if (isController) {
                        List<String> fieldModelNames = new ArrayList<>();
                        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                            Type fieldType = field.getCommonType();
                            if (fieldType == null || fieldType.isPrimitiveType()) {
                                return;
                            }
                            String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                    fieldType, cu, packageName, classMap, symbolSolver);
                            String typeName = resolvedType != null ? resolvedType : fieldType.asString();
                            boolean isModelType = "org.springframework.ui.Model".equals(typeName);
                            if (!isModelType && "Model".equals(fieldType.asString())) {
                                isModelType = cu.getImports().stream()
                                        .anyMatch(importDecl -> {
                                            String importName = importDecl.getNameAsString();
                                            return "org.springframework.ui.Model".equals(importName)
                                                    || "org.springframework.ui.*".equals(importName);
                                        });
                            }
                            if (isModelType) {
                                field.getVariables().forEach(var -> fieldModelNames.add(var.getNameAsString()));
                            }
                        });

                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            List<String> modelParameterNames = new ArrayList<>(fieldModelNames);
                            method.getParameters().forEach(parameter -> {
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        parameter.getType(), cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : parameter.getType().asString();
                                boolean isModelType = "org.springframework.ui.Model".equals(typeName);
                                if (!isModelType && "Model".equals(parameter.getType().asString())) {
                                    isModelType = cu.getImports().stream()
                                            .anyMatch(importDecl -> {
                                                String importName = importDecl.getNameAsString();
                                                return "org.springframework.ui.Model".equals(importName)
                                                        || "org.springframework.ui.*".equals(importName);
                                            });
                                }
                                if (isModelType) {
                                    String parameterName = parameter.getNameAsString();
                                    modelParameterNames.add(parameterName);
                                    String targetIdentifier = "org.springframework.ui.Model:parameter:"
                                            + method.getNameAsString() + ":" + parameterName;
                                    saveDependency(sourceClass, sourceFqn, targetIdentifier, "009_006", classMap);
                                }
                            });
                            method.findAll(VariableDeclarator.class).forEach(var -> {
                                Type varType = var.getType();
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        varType, cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : varType.asString();
                                boolean isModelType = "org.springframework.ui.Model".equals(typeName);
                                if (!isModelType && "Model".equals(varType.asString())) {
                                    isModelType = cu.getImports().stream()
                                            .anyMatch(importDecl -> {
                                                String importName = importDecl.getNameAsString();
                                                return "org.springframework.ui.Model".equals(importName)
                                                        || "org.springframework.ui.*".equals(importName);
                                            });
                                }
                                if (isModelType) {
                                    modelParameterNames.add(var.getNameAsString());
                                }
                            });

                            method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                                if (!"addAttribute".equals(methodCall.getNameAsString())) {
                                    return;
                                }
                                if (methodCall.getScope().isEmpty()) {
                                    return;
                                }
                                String scopeName = methodCall.getScope().get().toString();
                                if (scopeName.startsWith("this.")) {
                                    scopeName = scopeName.substring("this.".length());
                                }
                                if (modelParameterNames.contains(scopeName) || "model".equals(scopeName)) {
                                    String targetIdentifier = "org.springframework.ui.Model:addAttribute:"
                                            + method.getNameAsString();
                                    saveDependency(sourceClass, sourceFqn, targetIdentifier, "009_006", classMap);
                                }
                            });
                        });
                    }

                    // 011_001: Controller→Form（入力DTOの依存）
                    if (isController) {
                        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                            Type fieldType = field.getCommonType();
                            if (fieldType == null || fieldType.isPrimitiveType()) {
                                return;
                            }
                            String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                    fieldType, cu, packageName, classMap, symbolSolver);
                            String typeName = resolvedType != null ? resolvedType : fieldType.asString();
                            boolean isFormType = typeName.endsWith("Form") || fieldType.asString().endsWith("Form");
                            if (isFormType && !isPrimitiveOrBasicType(typeName)) {
                                saveDependency(sourceClass, sourceFqn, typeName, "011_001", classMap);
                            }
                        });

                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            method.getParameters().forEach(parameter -> {
                                boolean hasBindingAnnotation = parameter.getAnnotations().stream()
                                        .map(AnnotationExpr::getNameAsString)
                                        .anyMatch(annotationName -> annotationName.equals("ModelAttribute")
                                                || annotationName.equals("RequestBody")
                                                || annotationName.endsWith(".ModelAttribute")
                                                || annotationName.endsWith(".RequestBody"));

                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        parameter.getType(), cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : parameter.getType().asString();

                                boolean isFormType = typeName.endsWith("Form");
                                if (!isFormType) {
                                    String simpleName = parameter.getType().asString();
                                    isFormType = simpleName.endsWith("Form");
                                }

                                if ((hasBindingAnnotation || isFormType)
                                        && typeName != null
                                        && !typeName.isEmpty()
                                        && !isPrimitiveOrBasicType(typeName)) {
                                    saveDependency(sourceClass, sourceFqn, typeName, "011_001", classMap);
                                }
                            });

                            method.findAll(VariableDeclarator.class).forEach(var -> {
                                Type varType = var.getType();
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        varType, cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : varType.asString();
                                boolean isFormType = typeName.endsWith("Form") || varType.asString().endsWith("Form");
                                if (isFormType && !isPrimitiveOrBasicType(typeName)) {
                                    saveDependency(sourceClass, sourceFqn, typeName, "011_001", classMap);
                                }
                            });
                            method.findAll(ObjectCreationExpr.class).forEach(creationExpr -> {
                                Type createdType = creationExpr.getType();
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        createdType, cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : createdType.asString();
                                boolean isFormType = typeName.endsWith("Form")
                                        || createdType.asString().endsWith("Form");
                                if (isFormType && !isPrimitiveOrBasicType(typeName)) {
                                    saveDependency(sourceClass, sourceFqn, typeName, "011_001", classMap);
                                }
                            });
                        });
                    }

                    // 011_002: Controller→ViewModel（表示DTOの依存）
                    if (isController) {
                        // フィールド型からViewModelを検出
                        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                            Type fieldType = field.getCommonType();
                            if (fieldType != null && !fieldType.isPrimitiveType()) {
                                String typeName = TypeResolver.resolveFullyQualifiedName(
                                        fieldType, cu, packageName, classMap, symbolSolver);
                                if (isViewModelType(typeName)) {
                                    saveDependency(sourceClass, sourceFqn, typeName, "011_002", classMap);
                                }
                                extractGenericTypes(fieldType, cu, packageName, classMap, symbolSolver)
                                        .forEach(genericType -> {
                                            if (isViewModelType(genericType)) {
                                                saveDependency(sourceClass, sourceFqn, genericType, "011_002",
                                                        classMap);
                                            }
                                        });
                            }
                        });

                        classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                            // 戻り値型からViewModelを検出
                            Type returnType = method.getType();
                            if (returnType != null && !returnType.isVoidType() && !returnType.isPrimitiveType()) {
                                String returnTypeName = TypeResolver.resolveFullyQualifiedName(
                                        returnType, cu, packageName, classMap, symbolSolver);
                                if (isViewModelType(returnTypeName)) {
                                    saveDependency(sourceClass, sourceFqn, returnTypeName, "011_002", classMap);
                                }
                                extractGenericTypes(returnType, cu, packageName, classMap, symbolSolver)
                                        .forEach(genericType -> {
                                            if (isViewModelType(genericType)) {
                                                saveDependency(sourceClass, sourceFqn, genericType, "011_002",
                                                        classMap);
                                            }
                                        });
                            }

                            // addAttribute引数からViewModelを検出
                            List<String> modelParameterNames = new ArrayList<>();
                            method.getParameters().forEach(parameter -> {
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        parameter.getType(), cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : parameter.getType().asString();
                                boolean isModelType = "org.springframework.ui.Model".equals(typeName);
                                if (!isModelType && "Model".equals(parameter.getType().asString())) {
                                    isModelType = cu.getImports().stream()
                                            .anyMatch(importDecl -> {
                                                String importName = importDecl.getNameAsString();
                                                return "org.springframework.ui.Model".equals(importName)
                                                        || "org.springframework.ui.*".equals(importName);
                                            });
                                }
                                if (isModelType) {
                                    modelParameterNames.add(parameter.getNameAsString());
                                }
                            });

                            Map<String, String> localTypes = new HashMap<>();
                            method.getParameters().forEach(parameter -> {
                                String resolvedType = TypeResolver.resolveFullyQualifiedName(
                                        parameter.getType(), cu, packageName, classMap, symbolSolver);
                                String typeName = resolvedType != null ? resolvedType : parameter.getType().asString();
                                localTypes.put(parameter.getNameAsString(), typeName);
                            });
                            method.findAll(VariableDeclarator.class).forEach(var -> {
                                Type varType = var.getType();
                                String typeName = TypeResolver.resolveFullyQualifiedName(
                                        varType, cu, packageName, classMap, symbolSolver);
                                if (typeName != null && !typeName.isEmpty()) {
                                    localTypes.put(var.getNameAsString(), typeName);
                                }
                            });

                            method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                                if (!"addAttribute".equals(methodCall.getNameAsString())) {
                                    return;
                                }
                                if (methodCall.getScope().isEmpty()) {
                                    return;
                                }
                                String scopeName = methodCall.getScope().get().toString();
                                if (!modelParameterNames.contains(scopeName) && !"model".equals(scopeName)) {
                                    return;
                                }
                                if (methodCall.getArguments().size() < 2) {
                                    return;
                                }
                                Expression valueArg = methodCall.getArgument(1);
                                String viewModelType = resolveViewModelTypeFromExpression(
                                        valueArg, localTypes, classDecl, cu, packageName, classMap, symbolSolver);
                                if (viewModelType != null && isViewModelType(viewModelType)) {
                                    saveDependency(sourceClass, sourceFqn, viewModelType, "011_002", classMap);
                                }
                            });
                        });
                    }

                    // 009_005: テストスライス（@WebMvcTest等による限定コンテキスト依存）
                    if (className.endsWith("Test") || packageName.contains("test")) {
                        classDecl.getAnnotations().forEach(annotation -> {
                            String annotationName = annotation.getNameAsString();
                            if (annotationName.equals("WebMvcTest") || annotationName.equals("DataJpaTest")
                                    || annotationName.equals("JsonTest") || annotationName.equals("WebFluxTest")
                                    || annotationName.equals("DataJdbcTest") || annotationName.equals("JdbcTest")
                                    || annotationName.equals("DataMongoTest") || annotationName.equals("DataRedisTest")
                                    || annotationName.endsWith(".WebMvcTest") || annotationName.endsWith(".DataJpaTest")
                                    || annotationName.endsWith(".JsonTest") || annotationName.endsWith(".WebFluxTest")
                                    || annotationName.endsWith(".DataJdbcTest") || annotationName.endsWith(".JdbcTest")
                                    || annotationName.endsWith(".DataMongoTest")
                                    || annotationName.endsWith(".DataRedisTest")) {

                                String targetClasses = extractAnnotationValue(annotation, "value");
                                String targetIdentifier = annotationName;
                                if (targetClasses != null && !targetClasses.isEmpty()) {
                                    targetIdentifier += ":" + targetClasses;
                                }
                                saveDependency(sourceClass, sourceFqn, targetIdentifier, "009_005", classMap);
                            }
                        });
                    }
                });
            } catch (Exception e) {
                System.err.println("Failed to parse dependencies: " + javaFile + " - " + e.getMessage());
            }
        }
    }

    /**
     * オートコンフィグ解析（pom.xmlとMETA-INF/spring.factoriesの解析）
     * 
     * @param projectRoot プロジェクトルートパス
     * @param project     プロジェクトエンティティ
     * @param classMap    クラスマップ
     */
    private void parseAutoConfiguration(Path projectRoot, Project project, Map<String, ClassEntity> classMap) {
        try {
            // 1. pom.xmlからspring-boot-starter-*を抽出
            Path pomPath = projectRoot.resolve("pom.xml");
            if (Files.exists(pomPath)) {
                List<String> starters = extractSpringBootStarters(pomPath);
                for (String starter : starters) {
                    // プロジェクト全体に対してオートコンフィグ依存関係を記録
                    // sourceClassとして、プロジェクトのルートクラスを使用（便宜上、プロジェクト名を使用）
                    String projectName = projectRoot.getFileName().toString();
                    String sourceFqn = projectName + ".AutoConfiguration";

                    // 既存のクラスから適切なsourceClassを見つける、または仮のクラスエンティティを使用
                    ClassEntity sourceClass = findOrCreateProjectClass(project, classMap, sourceFqn, projectName);
                    saveDependency(sourceClass, sourceFqn, "starter:" + starter, "004_004", classMap);
                }
            }

            // 2. META-INF/spring.factoriesファイルを解析
            // プロジェクト内のresources/META-INF/spring.factoriesを検索
            Path resourcesPath = projectRoot.resolve("src/main/resources/META-INF/spring.factories");
            if (Files.exists(resourcesPath)) {
                parseSpringFactories(resourcesPath, project, classMap);
            }

            // target/classes/META-INF/spring.factoriesも検索（ビルド後のファイル）
            Path targetClassesPath = projectRoot.resolve("target/classes/META-INF/spring.factories");
            if (Files.exists(targetClassesPath)) {
                parseSpringFactories(targetClassesPath, project, classMap);
            }

            // 依存関係のJARファイル内のMETA-INF/spring.factoriesも検索
            // 注: 完全な実装にはMaven依存関係の解決が必要だが、簡易実装としてクラスパスを探索
            Path targetPath = projectRoot.resolve("target");
            if (Files.exists(targetPath)) {
                try (Stream<Path> paths = Files.walk(targetPath)) {
                    paths.filter(Files::isRegularFile)
                            .filter(p -> p.toString().contains("META-INF/spring.factories"))
                            .forEach(factoriesPath -> {
                                try {
                                    parseSpringFactories(factoriesPath, project, classMap);
                                } catch (Exception e) {
                                    System.err.println("Failed to parse spring.factories: " + factoriesPath + " - "
                                            + e.getMessage());
                                }
                            });
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse auto-configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * pom.xmlからspring-boot-starter-*の依存関係を抽出
     * 
     * @param pomPath pom.xmlファイルのパス
     * @return spring-boot-starter-*のリスト
     */
    private List<String> extractSpringBootStarters(Path pomPath) {
        List<String> starters = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomPath.toFile());

            NodeList dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dependency = (Element) dependencies.item(i);
                NodeList groupIds = dependency.getElementsByTagName("groupId");
                NodeList artifactIds = dependency.getElementsByTagName("artifactId");

                if (groupIds.getLength() > 0 && artifactIds.getLength() > 0) {
                    String groupId = groupIds.item(0).getTextContent().trim();
                    String artifactId = artifactIds.item(0).getTextContent().trim();

                    // spring-boot-starter-*を抽出
                    if ("org.springframework.boot".equals(groupId) && artifactId.startsWith("spring-boot-starter-")) {
                        starters.add(artifactId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse pom.xml: " + pomPath + " - " + e.getMessage());
        }
        return starters;
    }

    /**
     * META-INF/spring.factoriesファイルを解析
     * 
     * @param factoriesPath spring.factoriesファイルのパス
     * @param project       プロジェクトエンティティ
     * @param classMap      クラスマップ
     */
    private void parseSpringFactories(Path factoriesPath, Project project, Map<String, ClassEntity> classMap) {
        try {
            Properties properties = new Properties();
            try (InputStream is = Files.newInputStream(factoriesPath);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                properties.load(reader);
            }

            // org.springframework.boot.autoconfigure.EnableAutoConfiguration キーを取得
            String autoConfigurationKey = "org.springframework.boot.autoconfigure.EnableAutoConfiguration";
            String autoConfigurationClasses = properties.getProperty(autoConfigurationKey);

            if (autoConfigurationClasses != null && !autoConfigurationClasses.isEmpty()) {
                // カンマ区切りで複数のクラスが指定されている場合がある
                String[] classes = autoConfigurationClasses.split(",");
                for (String className : classes) {
                    className = className.trim();
                    if (!className.isEmpty()) {
                        String projectName = project.getRootPath();
                        if (projectName.contains(java.io.File.separator)) {
                            projectName = Paths.get(projectName).getFileName().toString();
                        }
                        String sourceFqn = projectName + ".AutoConfiguration";
                        ClassEntity sourceClass = findOrCreateProjectClass(project, classMap, sourceFqn, projectName);
                        saveDependency(sourceClass, sourceFqn, className, "004_004", classMap);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse spring.factories: " + factoriesPath + " - " + e.getMessage());
        }
    }

    /**
     * プロジェクトクラスを見つけるか作成する
     * 
     * @param project           プロジェクトエンティティ
     * @param classMap          クラスマップ
     * @param fullQualifiedName 完全修飾名
     * @param simpleName        簡易名
     * @return クラスエンティティ
     */
    private ClassEntity findOrCreateProjectClass(Project project, Map<String, ClassEntity> classMap,
            String fullQualifiedName, String simpleName) {
        String mapKey = fullQualifiedName;
        if (!classMap.containsKey(mapKey)) {
            // パッケージ情報を取得または作成
            PackageInfo packageInfo = packageInfoRepository.findByProject(project).stream()
                    .filter(pkg -> "<default>".equals(pkg.getFullName()))
                    .findFirst()
                    .orElseGet(() -> {
                        PackageInfo pkg = new PackageInfo(project, "<default>", "<default>");
                        return packageInfoRepository.save(pkg);
                    });

            ClassEntity classEntity = new ClassEntity(project, packageInfo, fullQualifiedName, simpleName);
            classEntity = classEntityRepository.save(classEntity);
            classMap.put(mapKey, classEntity);
            return classEntity;
        }
        return classMap.get(mapKey);
    }

    /**
     * ビルド依存解析（pom.xml/build.gradleの解析）
     * 
     * @param projectRoot プロジェクトルートパス
     * @param project     プロジェクトエンティティ
     * @param classMap    クラスマップ
     */
    private void parseBuildDependencies(Path projectRoot, Project project, Map<String, ClassEntity> classMap) {
        try {
            // 1. pom.xmlから依存関係を抽出
            Path pomPath = projectRoot.resolve("pom.xml");
            if (Files.exists(pomPath)) {
                List<MavenDependency> dependencies = extractMavenDependencies(pomPath);
                String projectName = projectRoot.getFileName().toString();
                String sourceFqn = projectName + ".BuildDependencies";
                ClassEntity sourceClass = findOrCreateProjectClass(project, classMap, sourceFqn, projectName);

                for (MavenDependency dependency : dependencies) {
                    // 依存関係を記録（groupId:artifactId:version:scope形式）
                    String targetIdentifier = String.format("%s:%s:%s:%s",
                            dependency.groupId,
                            dependency.artifactId,
                            dependency.version != null ? dependency.version : "",
                            dependency.scope != null ? dependency.scope : "compile");
                    saveDependency(sourceClass, sourceFqn, targetIdentifier, "004_005", classMap);
                }
            }

            // 2. build.gradleから依存関係を抽出（Gradleプロジェクトの場合）
            Path buildGradlePath = projectRoot.resolve("build.gradle");
            Path buildGradleKtsPath = projectRoot.resolve("build.gradle.kts");
            if (Files.exists(buildGradlePath)) {
                List<GradleDependency> gradleDependencies = extractGradleDependencies(buildGradlePath);
                String projectName = projectRoot.getFileName().toString();
                String sourceFqn = projectName + ".BuildDependencies";
                ClassEntity sourceClass = findOrCreateProjectClass(project, classMap, sourceFqn, projectName);

                for (GradleDependency dependency : gradleDependencies) {
                    String targetIdentifier = String.format("%s:%s:%s:%s",
                            dependency.configuration != null ? dependency.configuration : "implementation",
                            dependency.group != null ? dependency.group : "",
                            dependency.name != null ? dependency.name : "",
                            dependency.version != null ? dependency.version : "");
                    saveDependency(sourceClass, sourceFqn, targetIdentifier, "004_005", classMap);
                }
            } else if (Files.exists(buildGradleKtsPath)) {
                // build.gradle.ktsファイルも同様に処理（簡易実装）
                List<GradleDependency> gradleDependencies = extractGradleDependencies(buildGradleKtsPath);
                String projectName = projectRoot.getFileName().toString();
                String sourceFqn = projectName + ".BuildDependencies";
                ClassEntity sourceClass = findOrCreateProjectClass(project, classMap, sourceFqn, projectName);

                for (GradleDependency dependency : gradleDependencies) {
                    String targetIdentifier = String.format("%s:%s:%s:%s",
                            dependency.configuration != null ? dependency.configuration : "implementation",
                            dependency.group != null ? dependency.group : "",
                            dependency.name != null ? dependency.name : "",
                            dependency.version != null ? dependency.version : "");
                    saveDependency(sourceClass, sourceFqn, targetIdentifier, "004_005", classMap);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse build dependencies: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Maven依存関係情報を保持する内部クラス
     */
    private static class MavenDependency {
        String groupId;
        String artifactId;
        String version;
        String scope;

        MavenDependency(String groupId, String artifactId, String version, String scope) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
        }
    }

    /**
     * Gradle依存関係情報を保持する内部クラス
     */
    private static class GradleDependency {
        String configuration;
        String group;
        String name;
        String version;

        GradleDependency(String configuration, String group, String name, String version) {
            this.configuration = configuration;
            this.group = group;
            this.name = name;
            this.version = version;
        }
    }

    /**
     * pom.xmlからMaven依存関係を抽出
     * 
     * @param pomPath pom.xmlファイルのパス
     * @return Maven依存関係のリスト
     */
    private List<MavenDependency> extractMavenDependencies(Path pomPath) {
        List<MavenDependency> dependencies = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomPath.toFile());

            NodeList dependencyNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Element dependency = (Element) dependencyNodes.item(i);
                NodeList groupIds = dependency.getElementsByTagName("groupId");
                NodeList artifactIds = dependency.getElementsByTagName("artifactId");
                NodeList versions = dependency.getElementsByTagName("version");
                NodeList scopes = dependency.getElementsByTagName("scope");

                if (groupIds.getLength() > 0 && artifactIds.getLength() > 0) {
                    String groupId = groupIds.item(0).getTextContent().trim();
                    String artifactId = artifactIds.item(0).getTextContent().trim();
                    String version = versions.getLength() > 0 ? versions.item(0).getTextContent().trim() : null;
                    String scope = scopes.getLength() > 0 ? scopes.item(0).getTextContent().trim() : null;

                    // 親POMからの継承などでgroupIdが${...}の場合はスキップ
                    if (!groupId.contains("${") && !artifactId.contains("${")) {
                        dependencies.add(new MavenDependency(groupId, artifactId, version, scope));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse Maven dependencies from pom.xml: " + pomPath + " - " + e.getMessage());
        }
        return dependencies;
    }

    /**
     * build.gradle/build.gradle.ktsからGradle依存関係を抽出
     * 
     * @param gradlePath build.gradleファイルのパス
     * @return Gradle依存関係のリスト
     */
    private List<GradleDependency> extractGradleDependencies(Path gradlePath) {
        List<GradleDependency> dependencies = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(gradlePath, java.nio.charset.StandardCharsets.UTF_8);
            String currentConfiguration = null;

            for (String line : lines) {
                line = line.trim();

                // 依存関係の設定ブロックを検出（implementation, compile, runtime, testImplementation等）
                if (line.startsWith("implementation") || line.startsWith("compile")
                        || line.startsWith("runtime") || line.startsWith("testImplementation")
                        || line.startsWith("testCompile") || line.startsWith("api")
                        || line.startsWith("compileOnly") || line.startsWith("runtimeOnly")) {
                    // configuration名を抽出
                    int parenIndex = line.indexOf('(');
                    if (parenIndex > 0) {
                        currentConfiguration = line.substring(0, parenIndex).trim();
                    }
                }

                // 依存関係の定義を抽出
                // 例: implementation 'org.springframework.boot:spring-boot-starter-web'
                // 例: implementation group: 'org.springframework.boot', name:
                // 'spring-boot-starter-web', version: '3.0.0'
                if (line.contains("'") || line.contains("\"")) {
                    String dependencyStr = extractDependencyString(line);
                    if (dependencyStr != null && !dependencyStr.isEmpty()) {
                        GradleDependency dependency = parseGradleDependency(currentConfiguration, dependencyStr);
                        if (dependency != null) {
                            dependencies.add(dependency);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(
                    "Failed to parse Gradle dependencies from build.gradle: " + gradlePath + " - " + e.getMessage());
        }
        return dependencies;
    }

    /**
     * Gradle依存関係の文字列を抽出
     * 
     * @param line 行文字列
     * @return 依存関係の文字列
     */
    private String extractDependencyString(String line) {
        // '...' または "..." の文字列を抽出
        int startSingleQuote = line.indexOf("'");
        int startDoubleQuote = line.indexOf("\"");

        int start = -1;
        char quoteChar = 0;
        if (startSingleQuote >= 0 && (startDoubleQuote < 0 || startSingleQuote < startDoubleQuote)) {
            start = startSingleQuote + 1;
            quoteChar = '\'';
        } else if (startDoubleQuote >= 0) {
            start = startDoubleQuote + 1;
            quoteChar = '"';
        }

        if (start >= 0) {
            int end = line.indexOf(quoteChar, start);
            if (end > start) {
                return line.substring(start, end);
            }
        }
        return null;
    }

    /**
     * Gradle依存関係の文字列をパース
     * 
     * @param configuration 依存関係の設定（implementation等）
     * @param dependencyStr 依存関係の文字列（例:
     *                      "org.springframework.boot:spring-boot-starter-web"）
     * @return Gradle依存関係オブジェクト
     */
    private GradleDependency parseGradleDependency(String configuration, String dependencyStr) {
        // group:name:version 形式をパース
        String[] parts = dependencyStr.split(":");
        if (parts.length >= 2) {
            String group = parts[0];
            String name = parts[1];
            String version = parts.length >= 3 ? parts[2] : null;
            return new GradleDependency(configuration, group, name, version);
        }
        return null;
    }

    /**
     * ジェネリクス型引数を抽出する
     * 
     * @param type         型
     * @param cu           CompilationUnit（型解決に使用）
     * @param packageName  現在のパッケージ名
     * @param classMap     プロジェクト内のクラス情報（型解決に使用）
     * @param symbolSolver JavaSymbolSolver（Symbol Solverを使用する場合）
     * @return ジェネリクス型引数のリスト
     */
    private List<String> extractGenericTypes(Type type, CompilationUnit cu, String packageName,
            Map<String, ClassEntity> classMap, JavaSymbolSolver symbolSolver) {
        List<String> genericTypes = new ArrayList<>();
        if (type.isClassOrInterfaceType()) {
            type.asClassOrInterfaceType().getTypeArguments().ifPresent(args -> {
                args.forEach(arg -> {
                    if (arg.isClassOrInterfaceType()) {
                        String genericType = TypeResolver.resolveFullyQualifiedName(arg, cu, packageName, classMap,
                                symbolSolver);
                        if (genericType != null && !genericType.isEmpty()) {
                            genericTypes.add(genericType);
                        }
                    }
                });
            });
        }
        return genericTypes;
    }

    /**
     * メソッド呼び出しのスコープからクラス名を抽出する
     * 
     * @param scopeExpr   スコープ式
     * @param classDecl   クラス宣言（フィールドの型解決に使用）
     * @param cu          CompilationUnit（型解決に使用）
     * @param packageName 現在のパッケージ名
     * @param classMap    プロジェクト内のクラス情報（型解決に使用）
     * @return クラス名（完全修飾名または簡易名）、またはnull（自分自身の呼び出しなど）
     */
    private String extractClassNameFromScope(Expression scopeExpr, ClassOrInterfaceDeclaration classDecl,
            CompilationUnit cu, String packageName, Map<String, ClassEntity> classMap) {
        if (scopeExpr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) scopeExpr;
            Expression scopeValue = fieldAccess.getScope();

            if (scopeValue != null) {

                // 静的メソッド呼び出しの場合（ClassName.staticMethod()）
                if (scopeValue instanceof NameExpr) {
                    String className = ((NameExpr) scopeValue).getNameAsString();
                    // フィールドとして存在しない場合、クラス名として扱う
                    boolean isField = classDecl.getFields().stream()
                            .anyMatch(f -> f.getVariables().stream()
                                    .anyMatch(v -> v.getNameAsString().equals(className)));
                    if (!isField) {
                        return className; // 静的メソッド呼び出しのクラス名
                    }
                }

                // インスタンスメソッド呼び出しの場合（obj.method()）
                if (scopeValue instanceof NameExpr) {
                    String fieldName = ((NameExpr) scopeValue).getNameAsString();
                    // this, superの場合は除外
                    if (fieldName.equals("this") || fieldName.equals("super")) {
                        return null;
                    }

                    // フィールド名の場合、そのフィールドの型を取得
                    Optional<com.github.javaparser.ast.body.FieldDeclaration> field = classDecl.getFields().stream()
                            .filter(f -> f.getVariables().stream()
                                    .anyMatch(v -> v.getNameAsString().equals(fieldName)))
                            .findFirst();
                    if (field.isPresent()) {
                        Type fieldType = field.get().getCommonType();
                        String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap);
                        if (typeName != null && !typeName.isEmpty() && !isPrimitiveOrBasicType(typeName)) {
                            return typeName;
                        }
                    }
                } else if (scopeValue instanceof FieldAccessExpr) {
                    // ネストしたフィールドアクセス（this.field.method()など）
                    return extractClassNameFromScope(scopeValue, classDecl, cu, packageName, classMap);
                }
            }

            // FieldAccessExprの全体を文字列として取得
            String scopeStr = scopeExpr.toString();
            if (scopeStr.contains(".")) {
                String[] parts = scopeStr.split("\\.");
                // 最初の部分がクラス名の可能性が高い
                if (parts.length > 0) {
                    return parts[0];
                }
            }
        } else if (scopeExpr instanceof NameExpr) {
            // NameExprの場合、変数名またはクラス名として扱う
            String name = ((NameExpr) scopeExpr).getNameAsString();

            // this, superの場合は除外
            if (name.equals("this") || name.equals("super")) {
                return null;
            }

            // フィールド名として検索
            Optional<com.github.javaparser.ast.body.FieldDeclaration> field = classDecl.getFields().stream()
                    .filter(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(name)))
                    .findFirst();
            if (field.isPresent()) {
                Type fieldType = field.get().getCommonType();
                String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap);
                if (typeName != null && !typeName.isEmpty() && !isPrimitiveOrBasicType(typeName)) {
                    return typeName;
                }
            }

            // フィールドでもない場合、クラス名として扱う（静的メソッド呼び出しの可能性）
            // インポート文から解決を試みる
            return TypeResolver.resolveFromImports(name, cu, classMap)
                    .orElse(name);
        }

        // その他の場合は、toString()で文字列表現を取得
        String scopeStr = scopeExpr.toString();
        if (scopeStr.contains(".")) {
            // パッケージ名を含む場合、最初の部分をクラス名として扱う
            String[] parts = scopeStr.split("\\.");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        return scopeStr;
    }

    /**
     * 静的メソッド呼び出しのスコープからクラス名を抽出する
     * 静的メソッド呼び出し（ClassName.staticMethod()）を識別
     * 
     * @param scopeExpr   スコープ式
     * @param classDecl   クラス宣言（フィールドの型解決に使用）
     * @param cu          CompilationUnit（型解決に使用）
     * @param packageName 現在のパッケージ名
     * @param classMap    プロジェクト内のクラス情報（型解決に使用）
     * @return クラス名（静的メソッド呼び出しの場合のみ）、またはnull
     */
    private String extractStaticClassNameFromScope(Expression scopeExpr, ClassOrInterfaceDeclaration classDecl,
            CompilationUnit cu, String packageName, Map<String, ClassEntity> classMap) {
        if (scopeExpr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) scopeExpr;
            Expression scope = fieldAccess.getScope();

            if (scope != null) {
                // 静的メソッド呼び出し: ClassName.staticMethod()
                if (scope instanceof NameExpr) {
                    String name = ((NameExpr) scope).getNameAsString();
                    // フィールドとして存在しない場合、クラス名として扱う（静的メソッド呼び出し）
                    boolean isField = classDecl.getFields().stream()
                            .anyMatch(f -> f.getVariables().stream()
                                    .anyMatch(v -> v.getNameAsString().equals(name)));
                    if (!isField && !name.equals("this") && !name.equals("super")) {
                        // インポート文から解決を試みる
                        return TypeResolver.resolveFromImports(name, cu, classMap)
                                .orElse(name);
                    }
                } else if (scope instanceof FieldAccessExpr) {
                    // 完全修飾クラス名（package.ClassName.staticMethod()）
                    FieldAccessExpr nestedAccess = (FieldAccessExpr) scope;
                    Expression nestedScope = nestedAccess.getScope();
                    if (nestedScope instanceof NameExpr) {
                        // パッケージ名.クラス名の形式
                        String packageOrClass = ((NameExpr) nestedScope).getNameAsString();
                        String className = nestedAccess.getNameAsString();
                        // フィールドとして存在しない場合、クラス名として扱う
                        boolean isField = classDecl.getFields().stream()
                                .anyMatch(f -> f.getVariables().stream()
                                        .anyMatch(v -> v.getNameAsString().equals(packageOrClass)));
                        if (!isField) {
                            // package.ClassName の形式で返す
                            return packageOrClass + "." + className;
                        }
                    }
                }
            }
        } else if (scopeExpr instanceof NameExpr) {
            // NameExprの場合、クラス名として扱う（静的メソッド呼び出しの可能性）
            String name = ((NameExpr) scopeExpr).getNameAsString();

            // this, superの場合は除外
            if (name.equals("this") || name.equals("super")) {
                return null;
            }

            // フィールド名として存在しない場合、クラス名として扱う（静的メソッド呼び出し）
            boolean isField = classDecl.getFields().stream()
                    .anyMatch(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(name)));
            if (!isField) {
                // インポート文から解決を試みる
                return TypeResolver.resolveFromImports(name, cu, classMap)
                        .orElse(name);
            }
        }

        return null; // 静的メソッド呼び出しではない
    }

    /**
     * 定数参照（OtherClass.CONST）からクラス名を抽出する
     * 
     * @param fieldAccess FieldAccessExpr（定数参照）
     * @param classDecl   クラス宣言（フィールドの型解決に使用）
     * @param cu          CompilationUnit（型解決に使用）
     * @param packageName 現在のパッケージ名
     * @param classMap    プロジェクト内のクラス情報（型解決に使用）
     * @return クラス名（定数参照の場合のみ）、またはnull
     */
    private String extractConstantClassName(FieldAccessExpr fieldAccess, ClassOrInterfaceDeclaration classDecl,
            CompilationUnit cu, String packageName, Map<String, ClassEntity> classMap) {
        Expression scope = fieldAccess.getScope();
        String fieldName = fieldAccess.getNameAsString();

        // フィールド名が大文字で始まる（定数の命名規則）
        if (fieldName == null || fieldName.isEmpty() || !Character.isUpperCase(fieldName.charAt(0))) {
            return null;
        }

        if (scope instanceof NameExpr) {
            String className = ((NameExpr) scope).getNameAsString();

            // this, superの場合は除外
            if (className.equals("this") || className.equals("super")) {
                return null;
            }

            // 自分自身のクラスのフィールドかチェック
            boolean isLocalField = classDecl.getFields().stream()
                    .anyMatch(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(fieldName)));

            // ローカルフィールドではない場合、他クラスの定数参照として扱う
            if (!isLocalField) {
                // インポート文から解決を試みる（ワイルドカードインポートも含む）
                return TypeResolver.resolveFromImports(className, cu, classMap)
                        .orElse(className);
            }
        } else if (scope instanceof FieldAccessExpr) {
            // 完全修飾クラス名（package.ClassName.CONSTANT）
            FieldAccessExpr nestedAccess = (FieldAccessExpr) scope;
            Expression nestedScope = nestedAccess.getScope();

            if (nestedScope instanceof NameExpr) {
                String packageOrClass = ((NameExpr) nestedScope).getNameAsString();
                String className = nestedAccess.getNameAsString();

                // パッケージ名.クラス名の形式
                return packageOrClass + "." + className;
            }
        }

        return null; // 定数参照ではない
    }

    private boolean isPrimitiveOrBasicType(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return true;
        }
        // Javaの基本型とよくあるライブラリ型を除外
        String lower = typeName.toLowerCase();
        return lower.equals("string") || lower.equals("int") || lower.equals("integer")
                || lower.equals("long") || lower.equals("double") || lower.equals("float")
                || lower.equals("boolean") || lower.equals("char") || lower.equals("byte")
                || lower.equals("short") || lower.equals("void") || lower.equals("object")
                || typeName.startsWith("java.lang.") && (typeName.equals("java.lang.String")
                        || typeName.equals("java.lang.Integer") || typeName.equals("java.lang.Long")
                        || typeName.equals("java.lang.Double") || typeName.equals("java.lang.Float")
                        || typeName.equals("java.lang.Boolean") || typeName.equals("java.lang.Character")
                        || typeName.equals("java.lang.Byte") || typeName.equals("java.lang.Short")
                        || typeName.equals("java.lang.Object"));
    }

    /**
     * ノードに指定されたアノテーションが存在するかチェック（ヘルパーメソッド）
     * 
     * @param annotations    アノテーションのリスト
     * @param annotationName アノテーション名（簡易名、例: "Autowired", "Service"）
     * @return アノテーションが存在する場合true
     */
    private boolean hasAnnotation(List<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream()
                .anyMatch(annotation -> {
                    String name = annotation.getNameAsString();
                    // 完全修飾名または簡易名で一致をチェック
                    return name.equals(annotationName)
                            || name.endsWith("." + annotationName)
                            || name.equals("org.springframework.beans.factory.annotation." + annotationName)
                            || name.equals("org.springframework.stereotype." + annotationName)
                            || name.equals("org.springframework.context.annotation." + annotationName)
                            || name.equals("org.springframework.web.bind.annotation." + annotationName)
                            || name.equals("org.springframework.data.jpa.repository." + annotationName)
                            || name.equals("jakarta.persistence." + annotationName)
                            || name.equals("javax.persistence." + annotationName)
                            || name.equals("org.mapstruct." + annotationName)
                            || name.equals("org.springframework.beans.factory.annotation." + annotationName)
                            || name.equals("org.springframework.boot.context.properties." + annotationName)
                            || name.equals("org.springframework.boot.autoconfigure." + annotationName)
                            || name.equals("org.springframework.boot.autoconfigure.condition." + annotationName)
                            || name.equals("org.springframework.context.event." + annotationName)
                            || name.equals("org.springframework.cloud.openfeign." + annotationName)
                            || name.equals("org.springframework.kafka.annotation." + annotationName)
                            || name.equals("org.springframework.amqp.rabbit.annotation." + annotationName);
                });
    }

    /**
     * クラス宣言に指定されたアノテーションが存在するかチェック
     */
    private boolean hasAnnotation(ClassOrInterfaceDeclaration classDecl, String annotationName) {
        return hasAnnotation(classDecl.getAnnotations(), annotationName);
    }

    /**
     * メソッド宣言に指定されたアノテーションが存在するかチェック
     */
    private boolean hasAnnotation(MethodDeclaration method, String annotationName) {
        return hasAnnotation(method.getAnnotations(), annotationName);
    }

    /**
     * フィールド宣言に指定されたアノテーションが存在するかチェック
     */
    private boolean hasAnnotation(FieldDeclaration field, String annotationName) {
        return hasAnnotation(field.getAnnotations(), annotationName);
    }

    /**
     * コンストラクタ宣言に指定されたアノテーションが存在するかチェック
     */
    private boolean hasAnnotation(ConstructorDeclaration constructor, String annotationName) {
        return hasAnnotation(constructor.getAnnotations(), annotationName);
    }

    private void saveDependency(ClassEntity sourceClass, String sourceFqn, String targetIdentifier, String kindCode,
            Map<String, ClassEntity> classMap) {
        DependencyKindEntity kind = dependencyKindRepository.findByCode(kindCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown dependency kind code: " + kindCode));
        ClassDependency dependency = new ClassDependency(sourceClass, sourceFqn, targetIdentifier, kind);

        // targetIdentifierからtargetClassを解決
        // パッケージ名が空の場合のマップキーも考慮
        ClassEntity targetClass = classMap.get(targetIdentifier);
        if (targetClass == null && targetIdentifier.contains(".")) {
            // パッケージ名が空の場合の形式も試す
            String[] parts = targetIdentifier.split("\\.");
            if (parts.length > 1) {
                String simpleName = parts[parts.length - 1];
                String defaultKey = "<default>." + simpleName;
                targetClass = classMap.get(defaultKey);
            }
        }

        if (targetClass != null) {
            dependency.setTargetClass(targetClass);
        }

        classDependencyRepository.save(dependency);
    }

    private boolean isViewModelType(String typeName) {
        return typeName != null && typeName.endsWith("ViewModel");
    }

    private String resolveViewModelTypeFromExpression(Expression expression,
            Map<String, String> localTypes,
            ClassOrInterfaceDeclaration classDecl,
            CompilationUnit cu,
            String packageName,
            Map<String, ClassEntity> classMap,
            JavaSymbolSolver symbolSolver) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof ObjectCreationExpr) {
            Type type = ((ObjectCreationExpr) expression).getType();
            String typeName = TypeResolver.resolveFullyQualifiedName(type, cu, packageName, classMap, symbolSolver);
            return isViewModelType(typeName) ? typeName : null;
        }
        if (expression instanceof NameExpr) {
            String name = ((NameExpr) expression).getNameAsString();
            String localType = localTypes.get(name);
            if (isViewModelType(localType)) {
                return localType;
            }
            Optional<FieldDeclaration> field = classDecl.getFields().stream()
                    .filter(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(name)))
                    .findFirst();
            if (field.isPresent()) {
                Type fieldType = field.get().getCommonType();
                String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap,
                        symbolSolver);
                return isViewModelType(typeName) ? typeName : null;
            }
        }
        if (expression instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) expression;
            String name = fieldAccess.getNameAsString();
            Optional<FieldDeclaration> field = classDecl.getFields().stream()
                    .filter(f -> f.getVariables().stream()
                            .anyMatch(v -> v.getNameAsString().equals(name)))
                    .findFirst();
            if (field.isPresent()) {
                Type fieldType = field.get().getCommonType();
                String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap,
                        symbolSolver);
                return isViewModelType(typeName) ? typeName : null;
            }
        }
        if (expression instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) expression;
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();
                String localType = localTypes.get(scopeName);
                if (isViewModelType(localType)) {
                    return localType;
                }
                Optional<FieldDeclaration> field = classDecl.getFields().stream()
                        .filter(f -> f.getVariables().stream()
                                .anyMatch(v -> v.getNameAsString().equals(scopeName)))
                        .findFirst();
                if (field.isPresent()) {
                    Type fieldType = field.get().getCommonType();
                    String typeName = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap,
                            symbolSolver);
                    return isViewModelType(typeName) ? typeName : null;
                }
                if (scopeName.endsWith("ViewModel")) {
                    return TypeResolver.resolveFromImports(scopeName, cu, classMap).orElse(scopeName);
                }
            }
        }
        return null;
    }

    /**
     * アノテーションから属性値を抽出するヘルパーメソッド
     * 
     * @param annotation    アノテーション式
     * @param attributeName 属性名（例: "value", "prefix"）
     * @return 属性値、またはnull
     */
    private String extractAnnotationValue(AnnotationExpr annotation, String attributeName) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            SingleMemberAnnotationExpr singleMember = (SingleMemberAnnotationExpr) annotation;
            Expression memberValue = singleMember.getMemberValue();
            if (memberValue instanceof StringLiteralExpr) {
                return ((StringLiteralExpr) memberValue).getValue();
            }
        } else if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(attributeName))
                    .map(pair -> {
                        if (pair.getValue() instanceof StringLiteralExpr) {
                            return ((StringLiteralExpr) pair.getValue()).getValue();
                        }
                        return null;
                    })
                    .filter(value -> value != null)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * クラス宣言からアノテーション属性値を抽出するヘルパーメソッド
     * 
     * @param classDecl      クラス宣言
     * @param annotationName アノテーション名
     * @param attributeName  属性名
     * @return 属性値、またはnull
     */
    private String extractAnnotationAttributeValue(ClassOrInterfaceDeclaration classDecl, String annotationName,
            String attributeName) {
        return classDecl.getAnnotations().stream()
                .filter(annotation -> {
                    String name = annotation.getNameAsString();
                    return name.equals(annotationName)
                            || name.endsWith("." + annotationName)
                            || name.equals("org.springframework.boot.context.properties." + annotationName);
                })
                .map(annotation -> extractAnnotationValue(annotation, attributeName))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * クラス宣言からアノテーション属性配列値を抽出するヘルパーメソッド
     * 
     * @param classDecl      クラス宣言
     * @param annotationName アノテーション名
     * @param attributeName  属性名
     * @return 属性値の配列、またはnull
     */
    private String[] extractAnnotationAttributeArrayValue(ClassOrInterfaceDeclaration classDecl, String annotationName,
            String attributeName) {
        return classDecl.getAnnotations().stream()
                .filter(annotation -> {
                    String name = annotation.getNameAsString();
                    return name.equals(annotationName)
                            || name.endsWith("." + annotationName)
                            || name.equals("org.springframework.context.annotation." + annotationName)
                            || name.equals("org.springframework.boot.autoconfigure.condition." + annotationName);
                })
                .map(annotation -> {
                    if (annotation instanceof SingleMemberAnnotationExpr) {
                        SingleMemberAnnotationExpr singleMember = (SingleMemberAnnotationExpr) annotation;
                        Expression memberValue = singleMember.getMemberValue();
                        if (memberValue instanceof StringLiteralExpr) {
                            return new String[] { ((StringLiteralExpr) memberValue).getValue() };
                        }
                    } else if (annotation instanceof NormalAnnotationExpr) {
                        NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
                        return normal.getPairs().stream()
                                .filter(pair -> pair.getNameAsString().equals(attributeName))
                                .map(pair -> {
                                    if (pair.getValue() instanceof StringLiteralExpr) {
                                        return new String[] { ((StringLiteralExpr) pair.getValue()).getValue() };
                                    }
                                    return null;
                                })
                                .filter(value -> value != null)
                                .findFirst()
                                .orElse(null);
                    }
                    return null;
                })
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private PackageSummaryDto createPackageSummary(PackageInfo packageInfo) {
        List<ClassEntity> classes = classEntityRepository.findByPackageInfo(packageInfo);

        // 重複除去（fullQualifiedNameで比較）
        Set<ClassEntity> uniqueClasses = new LinkedHashSet<>(classes);
        List<String> classNames = uniqueClasses.stream()
                .map(ClassEntity::getSimpleName)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int classCount = classNames.size();

        // 依存関係の種類別件数（依存種類コードでソート）
        Map<String, Long> dependencyKindCounts = new TreeMap<>();
        List<DependencyKindEntity> allKinds = dependencyKindRepository.findAll();
        for (DependencyKindEntity kind : allKinds) {
            long count = classDependencyRepository.countByPackageInfoAndDependencyKindCode(packageInfo, kind.getCode());
            if (count > 0) {
                dependencyKindCounts.put(kind.getCode(), count);
            }
        }

        return new PackageSummaryDto(
                packageInfo.getFullName(),
                classCount,
                classNames,
                dependencyKindCounts);
    }

    /**
     * メンバー情報を抽出・保存する
     */
    private void parseMembers(List<Path> javaFiles, Path projectRoot, Map<String, ClassEntity> classMap) {
        JavaParser parser = new JavaParser();

        for (Path javaFile : javaFiles) {
            try {
                CompilationUnit cu = parser.parse(javaFile).getResult().orElse(null);
                if (cu == null) {
                    continue;
                }

                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");

                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                    String className = classDecl.getNameAsString();
                    String fullQualifiedName = packageName.isEmpty()
                            ? className
                            : packageName + "." + className;

                    String mapKey = packageName.isEmpty()
                            ? "<default>." + className
                            : fullQualifiedName;

                    ClassEntity classEntity = classMap.get(mapKey);
                    if (classEntity == null) {
                        return;
                    }

                    // フィールドを抽出
                    classDecl.getFields().forEach(field -> {
                        extractAndSaveField(field, classEntity, cu, packageName, classMap);
                    });

                    // メソッドを抽出
                    classDecl.getMethods().forEach(method -> {
                        extractAndSaveMethod(method, classEntity, cu, packageName, classMap);
                    });

                    // コンストラクタを抽出
                    classDecl.getConstructors().forEach(constructor -> {
                        extractAndSaveConstructor(constructor, classEntity, cu, packageName, classMap);
                    });
                });
            } catch (Exception e) {
                System.err.println("Failed to parse members from: " + javaFile + " - " + e.getMessage());
            }
        }
    }

    /**
     * フィールドを抽出・保存する
     */
    private void extractAndSaveField(FieldDeclaration field, ClassEntity classEntity,
            CompilationUnit cu, String packageName,
            Map<String, ClassEntity> classMap) {
        try {
            MemberType memberType = memberTypeRepository.findByCode("FIELD")
                    .orElseThrow(() -> new IllegalStateException("MemberType FIELD not found"));

            String visibility = getVisibility(field);
            Type fieldType = field.getCommonType();

            field.getVariables().forEach(variable -> {
                String fieldName = variable.getNameAsString();
                String returnType = TypeResolver.resolveFullyQualifiedName(fieldType, cu, packageName, classMap, null);

                Member member = new Member(classEntity, memberType, fieldName, returnType, visibility);
                member = memberRepository.save(member);

                // アノテーションを抽出・保存
                extractAndSaveAnnotations(field.getAnnotations(), member, null);
            });
        } catch (Exception e) {
            System.err.println("Failed to extract field: " + e.getMessage());
        }
    }

    /**
     * メソッドを抽出・保存する
     */
    private void extractAndSaveMethod(MethodDeclaration method, ClassEntity classEntity,
            CompilationUnit cu, String packageName,
            Map<String, ClassEntity> classMap) {
        try {
            MemberType memberType = memberTypeRepository.findByCode("METHOD")
                    .orElseThrow(() -> new IllegalStateException("MemberType METHOD not found"));

            String methodName = method.getNameAsString();
            String visibility = getVisibility(method);
            Type returnType = method.getType();
            String returnTypeFqn = returnType != null && !returnType.isVoidType()
                    ? TypeResolver.resolveFullyQualifiedName(returnType, cu, packageName, classMap, null)
                    : "void";

            Member member = new Member(classEntity, memberType, methodName, returnTypeFqn, visibility);
            member = memberRepository.save(member);

            // アノテーションを抽出・保存
            extractAndSaveAnnotations(method.getAnnotations(), member, null);
        } catch (Exception e) {
            System.err.println("Failed to extract method: " + e.getMessage());
        }
    }

    /**
     * コンストラクタを抽出・保存する
     */
    private void extractAndSaveConstructor(ConstructorDeclaration constructor, ClassEntity classEntity,
            CompilationUnit cu, String packageName,
            Map<String, ClassEntity> classMap) {
        try {
            MemberType memberType = memberTypeRepository.findByCode("CONSTRUCTOR")
                    .orElseThrow(() -> new IllegalStateException("MemberType CONSTRUCTOR not found"));

            String constructorName = constructor.getNameAsString();
            String visibility = getVisibility(constructor);
            // コンストラクタは戻り値型なし
            String returnType = null;

            Member member = new Member(classEntity, memberType, constructorName, returnType, visibility);
            member = memberRepository.save(member);

            // アノテーションを抽出・保存
            extractAndSaveAnnotations(constructor.getAnnotations(), member, null);
        } catch (Exception e) {
            System.err.println("Failed to extract constructor: " + e.getMessage());
        }
    }

    /**
     * 可視性を取得する（フィールド用）
     */
    private String getVisibility(FieldDeclaration field) {
        com.github.javaparser.ast.AccessSpecifier visibility = field.getAccessSpecifier();
        if (visibility == null) {
            return "PACKAGE_PRIVATE";
        }
        switch (visibility) {
            case PUBLIC:
                return "PUBLIC";
            case PROTECTED:
                return "PROTECTED";
            case PRIVATE:
                return "PRIVATE";
            default:
                return "PACKAGE_PRIVATE";
        }
    }

    /**
     * 可視性を取得する（メソッド用）
     */
    private String getVisibility(MethodDeclaration method) {
        com.github.javaparser.ast.AccessSpecifier visibility = method.getAccessSpecifier();
        if (visibility == null) {
            return "PACKAGE_PRIVATE";
        }
        switch (visibility) {
            case PUBLIC:
                return "PUBLIC";
            case PROTECTED:
                return "PROTECTED";
            case PRIVATE:
                return "PRIVATE";
            default:
                return "PACKAGE_PRIVATE";
        }
    }

    /**
     * 可視性を取得する（コンストラクタ用）
     */
    private String getVisibility(ConstructorDeclaration constructor) {
        com.github.javaparser.ast.AccessSpecifier visibility = constructor.getAccessSpecifier();
        if (visibility == null) {
            return "PACKAGE_PRIVATE";
        }
        switch (visibility) {
            case PUBLIC:
                return "PUBLIC";
            case PROTECTED:
                return "PROTECTED";
            case PRIVATE:
                return "PRIVATE";
            default:
                return "PACKAGE_PRIVATE";
        }
    }

    /**
     * アノテーションを抽出・保存する
     */
    private void extractAndSaveAnnotations(List<AnnotationExpr> annotations, Member member, ClassEntity classEntity) {
        for (AnnotationExpr annotation : annotations) {
            try {
                String annotationName = annotation.getNameAsString();

                // 完全修飾名を取得（可能な場合）
                if (annotation.getName().getQualifier().isPresent()) {
                    annotationName = annotation.getName().getQualifier().get().asString() + "." + annotationName;
                }

                final Annotation annotationEntity = annotationRepository.save(new Annotation(member, annotationName));

                // アノテーション属性を抽出
                if (annotation instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr normalAnn = (NormalAnnotationExpr) annotation;
                    normalAnn.getPairs().forEach(pair -> {
                        String attrName = pair.getNameAsString();
                        String attrValue = extractAnnotationAttributeValue(pair.getValue());

                        AnnotationAttribute attr = new AnnotationAttribute(
                                annotationEntity, attrName, attrValue);
                        annotationAttributeRepository.save(attr);
                    });
                } else if (annotation instanceof SingleMemberAnnotationExpr) {
                    SingleMemberAnnotationExpr singleAnn = (SingleMemberAnnotationExpr) annotation;
                    String attrValue = extractAnnotationAttributeValue(singleAnn.getMemberValue());

                    AnnotationAttribute attr = new AnnotationAttribute(
                            annotationEntity, "value", attrValue);
                    annotationAttributeRepository.save(attr);
                }
            } catch (Exception e) {
                System.err.println("Failed to extract annotation: " + e.getMessage());
            }
        }
    }

    /**
     * アノテーション属性値を文字列として抽出する
     */
    private String extractAnnotationAttributeValue(Expression expr) {
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        } else if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr) {
            com.github.javaparser.ast.expr.ArrayInitializerExpr arrayExpr = (com.github.javaparser.ast.expr.ArrayInitializerExpr) expr;
            return arrayExpr.getValues().stream()
                    .map(this::extractAnnotationAttributeValue)
                    .collect(Collectors.joining(","));
        } else {
            return expr.toString();
        }
    }
}