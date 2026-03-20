package com.example.springbootprojectanalyser.controller;

import com.example.springbootprojectanalyser.model.dto.ClassDependencyListItemDto;
import com.example.springbootprojectanalyser.model.dto.ClassDiagramDto;
import com.example.springbootprojectanalyser.model.dto.EndpointDto;
import com.example.springbootprojectanalyser.model.dto.SequenceInputDto;
import com.example.springbootprojectanalyser.model.form.ClassDiagramForm;
import com.example.springbootprojectanalyser.repository.EndpointRepository;
import com.example.springbootprojectanalyser.repository.ProjectRepository;
import com.example.springbootprojectanalyser.service.ClassDiagramService;
import com.example.springbootprojectanalyser.service.EndpointExtractionService;
import com.example.springbootprojectanalyser.service.SequenceSliceService;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * クラス図作成コントローラー
 */
@Controller
public class ClassDiagramController {

    private final EndpointExtractionService endpointExtractionService;
    private final ClassDiagramService classDiagramService;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final SequenceSliceService sequenceSliceService;

    public ClassDiagramController(
            EndpointExtractionService endpointExtractionService,
            ClassDiagramService classDiagramService,
            ProjectRepository projectRepository,
            EndpointRepository endpointRepository,
            SequenceSliceService sequenceSliceService) {
        this.endpointExtractionService = endpointExtractionService;
        this.classDiagramService = classDiagramService;
        this.projectRepository = projectRepository;
        this.endpointRepository = endpointRepository;
        this.sequenceSliceService = sequenceSliceService;
    }

    @GetMapping({"/classdiagram", "/classdiagram/"})
    public String index(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", ClassDiagramForm.empty());
        }
        // プロジェクト一覧を取得してモデルに追加
        model.addAttribute("projects", projectRepository.findAll());
        // フォームから選択済みエンドポイントIDを取得してモデルに追加（エンドポイント選択の保持のため）
        // リダイレクト属性のselectedEndpointIdが優先されるが、フォームにも設定する
        if (model.containsAttribute("form")) {
            ClassDiagramForm form = (ClassDiagramForm) model.getAttribute("form");
            if (form != null && form.selectedEndpointId() != null && !form.selectedEndpointId().isEmpty()) {
                // リダイレクト属性にselectedEndpointIdがない場合のみ、フォームから設定
                if (!model.containsAttribute("selectedEndpointId")) {
                    model.addAttribute("selectedEndpointId", form.selectedEndpointId());
                }
            }
        }
        return "classdiagram/index";
    }

    @PostMapping("/classdiagram/extract")
    public String extractEndpoints(
            @Valid @ModelAttribute("form") ClassDiagramForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.form", bindingResult);
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:/classdiagram/";
        }

        try {
            // エンドポイント情報を抽出
            String targetPackagePattern = "**"; // 全パッケージを対象
            List<EndpointDto> endpoints = endpointExtractionService.extractEndpoints(
                form.targetProjectPath(),
                targetPackagePattern
            );
            
            // プロジェクトIDを取得
            Long projectId = projectRepository.findByRootPath(form.targetProjectPath())
                .map(p -> p.getId())
                .orElseThrow(() -> new IllegalArgumentException("プロジェクトが見つかりません"));
            
            redirectAttributes.addFlashAttribute("endpoints", endpoints);
            redirectAttributes.addFlashAttribute("projectId", projectId);
            redirectAttributes.addFlashAttribute("form", form);
            
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "エラー: " + e.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:/classdiagram/";
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("error", "解析エラー: " + e.getMessage());
            redirectAttributes.addFlashAttribute("form", form);
            return "redirect:/classdiagram/";
        }

        return "redirect:/classdiagram/";
    }

    @PostMapping("/classdiagram/generate")
    public String generateClassDiagram(
            @RequestParam("selectedEndpointId") String selectedEndpointId,
            @RequestParam("projectId") Long projectId,
            RedirectAttributes redirectAttributes) {
        
        try {
            UUID endpointId = UUID.fromString(selectedEndpointId);
            
            // 選択されたエンドポイントの情報を取得（URI、HTTPメソッド、クラス名を保存するため）
            com.example.springbootprojectanalyser.model.entity.Endpoint selectedEndpoint = 
                endpointRepository.findById(selectedEndpointId)
                    .orElseThrow(() -> new IllegalArgumentException("エンドポイントが見つかりません: " + selectedEndpointId));
            
            String selectedUri = selectedEndpoint.getUri();
            String selectedHttpMethod = selectedEndpoint.getHttpMethod().getMethodName();
            String selectedClassName = selectedEndpoint.getClassEntity().getSimpleName();
            
            ClassDiagramDto classDiagram = classDiagramService.generateClassDiagram(endpointId, projectId);
            
            // プロジェクト情報を取得
            com.example.springbootprojectanalyser.model.entity.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("プロジェクトが見つかりません"));
            
            // エンドポイント情報を再取得（クラス図表示後もエンドポイント選択を可能にするため）
            String targetPackagePattern = "**"; // 全パッケージを対象
            List<EndpointDto> endpoints = endpointExtractionService.extractEndpoints(
                project.getRootPath(),
                targetPackagePattern
            );
            
            // 再取得後のエンドポイント一覧から、URI、HTTPメソッド、クラス名で一致するエンドポイントを探す
            String newSelectedEndpointId = endpoints.stream()
                .filter(e -> e.uri().equals(selectedUri) && 
                            e.httpMethodName().equals(selectedHttpMethod) && 
                            e.className().equals(selectedClassName))
                .map(EndpointDto::endpointId)
                .findFirst()
                .orElse(null);
            
            // フォーム情報を作成（プロジェクトパスを保持）
            ClassDiagramForm form = new ClassDiagramForm(project.getRootPath(), 
                newSelectedEndpointId != null ? newSelectedEndpointId : selectedEndpointId);
            
            redirectAttributes.addFlashAttribute("classDiagram", classDiagram);
            redirectAttributes.addFlashAttribute("selectedEndpointId", 
                newSelectedEndpointId != null ? newSelectedEndpointId : selectedEndpointId);
            redirectAttributes.addFlashAttribute("selectedEndpointUri", selectedUri);
            redirectAttributes.addFlashAttribute("selectedEndpointHttpMethod", selectedHttpMethod);
            redirectAttributes.addFlashAttribute("selectedEndpointClassName", selectedClassName);
            redirectAttributes.addFlashAttribute("projectId", projectId);
            redirectAttributes.addFlashAttribute("endpoints", endpoints);
            redirectAttributes.addFlashAttribute("form", form);
            
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "エラー: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("error", "クラス図生成エラー: " + e.getMessage());
        }

        return "redirect:/classdiagram/";
    }

    @GetMapping("/classdiagram/download-files")
    public org.springframework.http.ResponseEntity<byte[]> downloadRelatedFilesZip(
            @RequestParam("projectId") Long projectId,
            @RequestParam("endpointUri") String endpointUri,
            @RequestParam("httpMethod") String httpMethod) {
        
        try {
            // プロジェクト情報を取得
            com.example.springbootprojectanalyser.model.entity.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("プロジェクトが見つかりません"));
            
            // クラス図を生成してファイルパス情報を取得
            // エンドポイントを検索
            String targetPackagePattern = "**";
            List<EndpointDto> endpoints = endpointExtractionService.extractEndpoints(
                project.getRootPath(),
                targetPackagePattern
            );
            
            EndpointDto targetEndpoint = endpoints.stream()
                .filter(e -> e.uri().equals(endpointUri) && e.httpMethodName().equals(httpMethod))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("エンドポイントが見つかりません"));
            
            UUID endpointId = UUID.fromString(targetEndpoint.endpointId());
            ClassDiagramDto classDiagram = classDiagramService.generateClassDiagram(endpointId, projectId);
            String readmeContent = buildReadmeContent(
                project.getRootPath(),
                targetEndpoint,
                classDiagram
            );
            byte[] zipBytes = createZipBytes(project.getRootPath(), classDiagram.classFilePaths(), readmeContent);

            // ファイル名を生成（エンドポイント情報を含む）
            String fileName = generateFileName(endpointUri, httpMethod);

            // レスポンスヘッダーを設定
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", fileName);

            return org.springframework.http.ResponseEntity.ok()
                .headers(headers)
                .body(zipBytes);
                
        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(("エラー: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/classdiagram/download-sequence")
    public org.springframework.http.ResponseEntity<byte[]> downloadSequenceInputJson(
            @RequestParam("projectId") Long projectId,
            @RequestParam("selectedEndpointId") String selectedEndpointId) {
        try {
            com.example.springbootprojectanalyser.model.entity.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("プロジェクトが見つかりません"));

            com.example.springbootprojectanalyser.model.entity.Endpoint endpointEntity =
                endpointRepository.findById(selectedEndpointId)
                    .orElseThrow(() -> new IllegalArgumentException("エンドポイントが見つかりません: " + selectedEndpointId));

            EndpointDto endpointDto = new EndpointDto(
                endpointEntity.getEndpointId(),
                endpointEntity.getClassEntity() != null ? endpointEntity.getClassEntity().getId() : null,
                endpointEntity.getClassEntity() != null ? endpointEntity.getClassEntity().getSimpleName() : "",
                endpointEntity.getUri(),
                endpointEntity.getHttpMethod() != null ? endpointEntity.getHttpMethod().getId() : null,
                endpointEntity.getHttpMethod() != null ? endpointEntity.getHttpMethod().getMethodName() : ""
            );

            UUID endpointId = UUID.fromString(selectedEndpointId);
            ClassDiagramDto classDiagram = classDiagramService.generateClassDiagram(endpointId, projectId);

            SequenceInputDto sequenceInput = sequenceSliceService.generateSequenceInput(
                classDiagram,
                endpointDto,
                project.getRootPath()
            );

            ObjectMapper objectMapper = new ObjectMapper();
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sequenceInput);

            String fileName = generateSequenceFileName(endpointDto.uri(), endpointDto.httpMethodName());
            byte[] zipBytes = createSequenceZipBytes(jsonBytes);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", fileName);

            return org.springframework.http.ResponseEntity.ok()
                .headers(headers)
                .body(zipBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(("エラー: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 関連ファイルをZIP化する
     */
    private byte[] createZipBytes(String projectRootPath, Map<String, String> classFilePaths, String readmeContent) {
        Path projectRoot = java.nio.file.Paths.get(projectRootPath);

        Map<String, Path> javaFileCache = new HashMap<>();
        try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(projectRoot)) {
            paths.filter(java.nio.file.Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("target"))
                .filter(p -> !p.toString().contains(".git"))
                .forEach(javaFile -> {
                    try {
                        String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');
                        javaFileCache.put(relativePath, javaFile);
                    } catch (Exception e) {
                        // 相対パス取得エラーは無視
                    }
                });
        } catch (Exception e) {
            // ファイル検索エラーは無視
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            Set<String> addedEntries = new HashSet<>();
            if (readmeContent != null && !readmeContent.isEmpty()) {
                String readmeName = "README.md";
                if (addedEntries.add(readmeName)) {
                    ZipEntry readmeEntry = new ZipEntry(readmeName);
                    zos.putNextEntry(readmeEntry);
                    zos.write(readmeContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            for (Map.Entry<String, String> entry : classFilePaths.entrySet()) {
                ResolvedFile resolved = resolveFile(projectRoot, javaFileCache, entry.getKey(), entry.getValue());
                String entryName = normalizeZipEntryName(resolved.entryPath());

                if (resolved.exists()) {
                    if (addedEntries.add(entryName)) {
                        ZipEntry zipEntry = new ZipEntry(entryName);
                        zos.putNextEntry(zipEntry);
                        byte[] content = java.nio.file.Files.readAllBytes(resolved.path());
                        zos.write(content);
                        zos.closeEntry();
                    }
                } else {
                    String missingEntryName = entryName + ".missing.txt";
                    if (addedEntries.add(missingEntryName)) {
                        ZipEntry zipEntry = new ZipEntry(missingEntryName);
                        zos.putNextEntry(zipEntry);
                        String message = "File not found: " + resolved.entryPath() + "\nFQN: " + entry.getKey() + "\n";
                        zos.write(message.getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                    }
                }
            }
            zos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("ZIP生成に失敗しました: " + e.getMessage(), e);
        }
    }

    private String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return "unknown.java";
        }
        return entryName.replace('\\', '/');
    }

    private ResolvedFile resolveFile(Path projectRoot, Map<String, Path> javaFileCache, String fqn, String filePath) {
        Path fullPath = projectRoot.resolve(filePath);
        String foundPath = filePath;

        if (java.nio.file.Files.exists(fullPath) && java.nio.file.Files.isRegularFile(fullPath)) {
            return new ResolvedFile(fullPath, foundPath, true);
        }

        String[] possiblePaths = generatePossiblePaths(fqn);
        for (String possiblePath : possiblePaths) {
            Path possibleFullPath = projectRoot.resolve(possiblePath);
            if (java.nio.file.Files.exists(possibleFullPath) && java.nio.file.Files.isRegularFile(possibleFullPath)) {
                return new ResolvedFile(possibleFullPath, possiblePath, true);
            }
        }

        int lastDotIndex = fqn.lastIndexOf('.');
        String className = lastDotIndex >= 0 ? fqn.substring(lastDotIndex + 1) : fqn;
        String searchFileName = className + ".java";

        for (Map.Entry<String, Path> cacheEntry : javaFileCache.entrySet()) {
            if (cacheEntry.getKey().endsWith("/" + searchFileName) || cacheEntry.getKey().equals(searchFileName)) {
                try {
                    JavaParser parser = new JavaParser();
                    CompilationUnit cu = parser.parse(cacheEntry.getValue()).getResult().orElse(null);
                    if (cu != null) {
                        String filePackageName = cu.getPackageDeclaration()
                            .map(pd -> pd.getNameAsString())
                            .orElse("");
                        List<ClassOrInterfaceDeclaration> classDecls =
                            cu.findAll(ClassOrInterfaceDeclaration.class);
                        for (ClassOrInterfaceDeclaration classDecl : classDecls) {
                            String fileClassName = classDecl.getNameAsString();
                            String fileFqn = filePackageName.isEmpty()
                                ? fileClassName
                                : filePackageName + "." + fileClassName;
                            if (fileFqn.equals(fqn)) {
                                return new ResolvedFile(cacheEntry.getValue(), cacheEntry.getKey(), true);
                            }
                        }
                    }
                } catch (Exception e) {
                    // パースエラーは無視
                }
            }
        }

        return new ResolvedFile(fullPath, foundPath, false);
    }

    private String buildReadmeContent(
        String projectRootPath,
        EndpointDto endpoint,
        ClassDiagramDto classDiagram) {
        StringBuilder sb = new StringBuilder();
        sb.append("# クラス図作成機能 解析結果\n\n");

        sb.append("## 対象プロジェクト（パス）\n");
        sb.append(projectRootPath).append("\n\n");

        sb.append("## エンドポイント（URI(HTTPメソッド) : クラス名）\n");
        String endpointUri = endpoint != null ? endpoint.uri() : "";
        String httpMethod = endpoint != null ? endpoint.httpMethodName() : "";
        String className = endpoint != null ? endpoint.className() : "";
        sb.append(endpointUri).append("(").append(httpMethod).append(") : ").append(className).append("\n\n");

        sb.append("## 生成日\n");
        sb.append(java.time.LocalDateTime.now().toString()).append("\n\n");

        sb.append("## 解析結果（mermaidクラス図）\n");
        sb.append("```mermaid\n");
        if (classDiagram != null && classDiagram.classDiagramText() != null) {
            sb.append(classDiagram.classDiagramText());
            if (!classDiagram.classDiagramText().endsWith("\n")) {
                sb.append("\n");
            }
        }
        sb.append("```\n\n");

        sb.append("## 依存関係一覧\n");
        sb.append("| 依存種類コード | 依存種類名 | 親クラス | 子クラス |\n");
        sb.append("|---|---|---|---|\n");
        if (classDiagram == null || classDiagram.dependencyList() == null || classDiagram.dependencyList().isEmpty()) {
            sb.append("| - | - | - | - |\n");
        } else {
            for (ClassDependencyListItemDto item : classDiagram.dependencyList()) {
                sb.append("| ")
                    .append(safeMarkdownCell(item.dependencyKindCode()))
                    .append(" | ")
                    .append(safeMarkdownCell(item.dependencyKindName()))
                    .append(" | ")
                    .append(safeMarkdownCell(item.parentClassName()))
                    .append(" | ")
                    .append(safeMarkdownCell(item.childClassName()))
                    .append(" |\n");
            }
        }
        sb.append("\n");

        sb.append("## 関連ファイル一覧\n");
        if (classDiagram == null || classDiagram.classFilePaths() == null || classDiagram.classFilePaths().isEmpty()) {
            sb.append("- なし\n");
        } else {
            for (Map.Entry<String, String> entry : classDiagram.classFilePaths().entrySet()) {
                sb.append("- ").append(entry.getValue())
                    .append(" (").append(entry.getKey()).append(")\n");
            }
        }

        return sb.toString();
    }

    private String safeMarkdownCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    /**
     * クラスのFQNから可能なファイルパスを生成する
     */
    private String[] generatePossiblePaths(String fqn) {
        int lastDotIndex = fqn.lastIndexOf('.');
        String packageName = lastDotIndex >= 0 ? fqn.substring(0, lastDotIndex) : "";
        String className = lastDotIndex >= 0 ? fqn.substring(lastDotIndex + 1) : fqn;
        String packagePath = packageName.replace('.', '/');
        
        List<String> paths = new ArrayList<>();
        
        // src/main/java と src/test/java の両方を試す
        if (packagePath.isEmpty()) {
            paths.add("src/main/java/" + className + ".java");
            paths.add("src/test/java/" + className + ".java");
        } else {
            paths.add("src/main/java/" + packagePath + "/" + className + ".java");
            paths.add("src/test/java/" + packagePath + "/" + className + ".java");
        }
        
        return paths.toArray(new String[0]);
    }

    /**
     * ファイル名を生成する（エンドポイント情報を含む）
     */
    private String generateFileName(String endpointUri, String httpMethod) {
        // URIからファイル名に使用できない文字を置換
        String safeUri = endpointUri.replace("/", "_")
                                   .replace("\\", "_")
                                   .replace(":", "_")
                                   .replace("*", "_")
                                   .replace("?", "_")
                                   .replace("\"", "_")
                                   .replace("<", "_")
                                   .replace(">", "_")
                                   .replace("|", "_");
        
        // 空文字列の場合は"root"に置換
        if (safeUri.isEmpty() || safeUri.equals("_")) {
            safeUri = "root";
        }
        
        // 先頭のアンダースコアを削除
        safeUri = safeUri.replaceAll("^_+", "");
        
        String fileName = safeUri + "(" + httpMethod + ").zip";
        return fileName;
    }

    private String generateSequenceFileName(String endpointUri, String httpMethod) {
        String safeUri = endpointUri.replace("/", "_")
                                   .replace("\\", "_")
                                   .replace(":", "_")
                                   .replace("*", "_")
                                   .replace("?", "_")
                                   .replace("\"", "_")
                                   .replace("<", "_")
                                   .replace(">", "_")
                                   .replace("|", "_");
        if (safeUri.isEmpty() || safeUri.equals("_")) {
            safeUri = "root";
        }
        safeUri = safeUri.replaceAll("^_+", "");
        return "sequence-input-" + safeUri + "(" + httpMethod + ").zip";
    }

    private byte[] createSequenceZipBytes(byte[] jsonBytes) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ZipEntry entry = new ZipEntry("sequence_input.json");
            zos.putNextEntry(entry);
            zos.write(jsonBytes);
            zos.closeEntry();
            zos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("ZIP生成に失敗しました: " + e.getMessage(), e);
        }
    }

    private record ResolvedFile(Path path, String entryPath, boolean exists) {
    }
}

