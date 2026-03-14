package com.zwiki.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 全量源码预读服务
 * 在Java侧预读项目全部源码文件，直接注入到LLM prompt中，
 * 避免LLM依赖工具调用来读取文件（不可靠且浪费token）。
 *
 * @author pai
 */
@Service
@Slf4j
public class CodebasePreReader {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "build", "dist", "out", ".git", ".idea", ".vscode",
            "node_modules", "__pycache__", ".gradle", "logs", "log",
            ".svn", "venv", ".venv", "test", "tests", "__tests__",
            ".settings", ".project", ".classpath", "bin", ".cache",
            ".next", ".nuxt", "coverage", ".nyc_output"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            "class", "jar", "war", "zip", "gz", "tar", "rar", "7z",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp", "tiff",
            "exe", "dll", "so", "dylib", "node", "pdf",
            "lock", "map", "min.js", "min.css",
            "woff", "woff2", "ttf", "eot", "otf",
            "mp3", "mp4", "avi", "mov", "wav", "flac",
            "db", "sqlite", "mdb", "dat", "bin"
    );

    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            ".DS_Store", "Thumbs.db", ".gitignore", ".gitattributes",
            "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd"
    );

    // 核心源码扩展名 — 优先级最高
    private static final Set<String> CORE_SOURCE_EXTENSIONS = Set.of(
            "java", "py", "go", "ts", "tsx", "js", "jsx",
            "rs", "kt", "scala", "cs", "cpp", "c", "h", "hpp",
            "rb", "php", "swift", "dart", "lua", "ex", "exs",
            "vue", "svelte"
    );

    // 配置文件扩展名 — 优先级次高
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            "yml", "yaml", "xml", "json", "toml", "ini", "cfg",
            "properties", "env", "conf", "gradle", "cmake"
    );

    // 文档文件名 — 优先级第三
    private static final Set<String> DOC_FILE_NAMES = Set.of(
            "README.md", "readme.md", "README.rst", "CHANGELOG.md",
            "CONTRIBUTING.md", "LICENSE", "Makefile", "Dockerfile",
            "docker-compose.yml", "docker-compose.yaml"
    );

    private static final int MAX_SINGLE_FILE_CHARS = 100000;
    private static final int MAX_TOTAL_CHARS = 1000000;
    private static final int MAX_FILES = 300;

    // 关键结构文件限制（用于目录生成，远小于全量源码）
    private static final int KEY_FILES_MAX_SINGLE_CHARS = 30000;
    private static final int KEY_FILES_MAX_TOTAL_CHARS = 80000;

    // 指定文件读取限制（用于文档生成，只读依赖文件）
    private static final int SPECIFIC_FILES_MAX_SINGLE_CHARS = 30000;
    private static final int SPECIFIC_FILES_MAX_TOTAL_CHARS = 150000;

    // 分段文档生成阈值和限制
    public static final int SEGMENTED_DOC_THRESHOLD = 60000;
    private static final int SEGMENT_MAX_CHARS = 50000;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> keyFilesCache = new ConcurrentHashMap<>();

    /**
     * 文件分组结果，用于分段文档生成
     */
    public record FileGroup(String groupName, List<String> files, int totalChars) {}

    /**
     * 将文件列表按类型分组，用于分段文档生成。
     * 当dependent_files总量超过SEGMENTED_DOC_THRESHOLD时调用。
     * 
     * @param projectRoot 项目根目录
     * @param relativePaths 文件相对路径列表
     * @return 分组后的文件列表，每组不超过SEGMENT_MAX_CHARS
     */
    public List<FileGroup> groupFilesForSegmentedDocGen(String projectRoot, List<String> relativePaths) {
        if (projectRoot == null || projectRoot.isBlank() || relativePaths == null || relativePaths.isEmpty()) {
            return List.of();
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        // 按文件类型分类
        List<String> controllers = new ArrayList<>();
        List<String> services = new ArrayList<>();
        List<String> repositories = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> configs = new ArrayList<>();
        List<String> others = new ArrayList<>();

        for (String relPath : relativePaths) {
            if (relPath == null || relPath.isBlank()) continue;
            String pathLower = relPath.toLowerCase(Locale.ROOT);
            
            if (pathLower.contains("controller") || pathLower.contains("resource") || pathLower.contains("api")) {
                controllers.add(relPath);
            } else if (pathLower.contains("service") && !pathLower.contains("repository")) {
                services.add(relPath);
            } else if (pathLower.contains("repository") || pathLower.contains("mapper") || pathLower.contains("dao")) {
                repositories.add(relPath);
            } else if (pathLower.contains("entity") || pathLower.contains("model") || pathLower.contains("dto") || pathLower.contains("vo")) {
                entities.add(relPath);
            } else if (pathLower.contains("config") || pathLower.endsWith(".yml") || pathLower.endsWith(".yaml") || pathLower.endsWith(".properties") || pathLower.endsWith(".xml")) {
                configs.add(relPath);
            } else {
                others.add(relPath);
            }
        }

        // 构建分组，控制每组大小
        List<FileGroup> groups = new ArrayList<>();
        addGroupIfNotEmpty(groups, "Controllers & APIs", controllers, root);
        addGroupIfNotEmpty(groups, "Services", services, root);
        addGroupIfNotEmpty(groups, "Repositories & Mappers", repositories, root);
        addGroupIfNotEmpty(groups, "Entities & DTOs", entities, root);
        addGroupIfNotEmpty(groups, "Configurations", configs, root);
        addGroupIfNotEmpty(groups, "Other Files", others, root);

        log.info("groupFilesForSegmentedDocGen: 分为 {} 组, 共 {} 个文件", groups.size(), relativePaths.size());
        return groups;
    }

    private void addGroupIfNotEmpty(List<FileGroup> groups, String groupName, List<String> files, Path root) {
        if (files == null || files.isEmpty()) {
            return;
        }

        // 计算该组文件的总字符数（估算）
        int totalChars = 0;
        List<String> currentGroup = new ArrayList<>();
        int groupIndex = 1;

        for (String relPath : files) {
            Path file = root.resolve(relPath.trim().replace('\\', '/'));
            int fileChars = 0;
            if (Files.exists(file) && Files.isRegularFile(file)) {
                try {
                    fileChars = (int) Math.min(Files.size(file), SPECIFIC_FILES_MAX_SINGLE_CHARS);
                } catch (IOException e) {
                    fileChars = 5000; // 估算值
                }
            }

            if (totalChars + fileChars > SEGMENT_MAX_CHARS && !currentGroup.isEmpty()) {
                // 当前组已满，创建新组
                groups.add(new FileGroup(groupName + (groupIndex > 1 ? " (" + groupIndex + ")" : ""), 
                        new ArrayList<>(currentGroup), totalChars));
                currentGroup.clear();
                totalChars = 0;
                groupIndex++;
            }

            currentGroup.add(relPath);
            totalChars += fileChars;
        }

        // 添加最后一组
        if (!currentGroup.isEmpty()) {
            groups.add(new FileGroup(groupName + (groupIndex > 1 ? " (" + groupIndex + ")" : ""), 
                    currentGroup, totalChars));
        }
    }

    /**
     * 追踪给定入口文件中的import依赖，返回被引用的文件路径列表。
     * 用于动态补充dependent_files，提升文档内容的完整性。
     * 
     * @param projectRoot 项目根目录
     * @param entryFiles 入口文件的相对路径列表
     * @param maxDepth 追踪深度（默认1层）
     * @param maxFiles 最多返回的文件数
     * @return 被引用的文件相对路径集合（不含入口文件本身）
     */
    public Set<String> traceImports(String projectRoot, List<String> entryFiles, int maxDepth, int maxFiles) {
        Set<String> result = new LinkedHashSet<>();
        if (projectRoot == null || projectRoot.isBlank() || entryFiles == null || entryFiles.isEmpty()) {
            return result;
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return result;
        }

        // 构建项目文件索引（类名 -> 相对路径）
        Map<String, String> classNameToPath = buildClassNameIndex(root);

        Set<String> visited = new HashSet<>(entryFiles);
        List<String> currentLevel = new ArrayList<>(entryFiles);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty() && result.size() < maxFiles; depth++) {
            List<String> nextLevel = new ArrayList<>();
            
            for (String relPath : currentLevel) {
                if (result.size() >= maxFiles) break;
                
                Path file = root.resolve(relPath.trim().replace('\\', '/'));
                if (!Files.exists(file) || !Files.isRegularFile(file)) continue;

                Set<String> imports = extractImports(file, classNameToPath, root);
                for (String imp : imports) {
                    if (!visited.contains(imp) && result.size() < maxFiles) {
                        result.add(imp);
                        visited.add(imp);
                        nextLevel.add(imp);
                    }
                }
            }
            currentLevel = nextLevel;
        }

        log.info("traceImports: 从 {} 个入口文件追踪到 {} 个依赖文件 (depth={})", 
                entryFiles.size(), result.size(), maxDepth);
        return result;
    }

    private Map<String, String> buildClassNameIndex(Path root) {
        Map<String, String> index = new HashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isExcludedPath(root, p))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".java") || name.endsWith(".kt") || 
                               name.endsWith(".ts") || name.endsWith(".tsx") ||
                               name.endsWith(".js") || name.endsWith(".jsx") ||
                               name.endsWith(".py") || name.endsWith(".go");
                    })
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String baseName = fileName.contains(".") 
                                ? fileName.substring(0, fileName.lastIndexOf('.')) 
                                : fileName;
                        String relPath = root.relativize(p).toString().replace('\\', '/');
                        // 存储多种可能的引用方式
                        index.put(baseName, relPath);
                        index.put(baseName.toLowerCase(Locale.ROOT), relPath);
                    });
        } catch (IOException e) {
            log.warn("buildClassNameIndex failed: {}", e.getMessage());
        }
        return index;
    }

    private Set<String> extractImports(Path file, Map<String, String> classNameToPath, Path root) {
        Set<String> imports = new LinkedHashSet<>();
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        
        try {
            String content = Files.readString(file);
            if (content.length() > 50000) {
                content = content.substring(0, 50000);
            }

            Pattern importPattern;
            if (fileName.endsWith(".java") || fileName.endsWith(".kt")) {
                // Java/Kotlin: import com.example.ClassName;
                importPattern = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?;");
            } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx") || 
                       fileName.endsWith(".js") || fileName.endsWith(".jsx")) {
                // TS/JS: import { X } from './path' or import X from './path'
                importPattern = Pattern.compile("import\\s+.*?from\\s+['\"]([^'\"]+)['\"]");
            } else if (fileName.endsWith(".py")) {
                // Python: from module import X or import module
                importPattern = Pattern.compile("(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
            } else if (fileName.endsWith(".go")) {
                // Go: import "package/path"
                importPattern = Pattern.compile("import\\s+[\"']([^\"']+)[\"']");
            } else {
                return imports;
            }

            Matcher m = importPattern.matcher(content);
            while (m.find()) {
                String importPath = m.group(1);
                if (importPath == null && m.groupCount() > 1) {
                    importPath = m.group(2);
                }
                if (importPath == null) continue;

                // 尝试解析为项目内文件
                String resolved = resolveImportToFile(importPath, file, classNameToPath, root);
                if (resolved != null) {
                    imports.add(resolved);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to read file for import extraction: {}", file);
        }
        return imports;
    }

    private String resolveImportToFile(String importPath, Path sourceFile, 
                                        Map<String, String> classNameToPath, Path root) {
        // For Java: extract class name from fully qualified name
        if (importPath.contains(".")) {
            String className = importPath.substring(importPath.lastIndexOf('.') + 1);
            if (classNameToPath.containsKey(className)) {
                return classNameToPath.get(className);
            }
        }
        
        // Direct class name lookup
        if (classNameToPath.containsKey(importPath)) {
            return classNameToPath.get(importPath);
        }
        
        // For relative paths (JS/TS)
        if (importPath.startsWith("./") || importPath.startsWith("../")) {
            Path sourceDir = sourceFile.getParent();
            if (sourceDir == null) return null;
            
            Path resolved = sourceDir.resolve(importPath).normalize();
            // Try with common extensions
            for (String ext : List.of("", ".ts", ".tsx", ".js", ".jsx", ".vue")) {
                Path candidate = Paths.get(resolved.toString() + ext);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    try {
                        String relPath = root.relativize(candidate).toString().replace('\\', '/');
                        return relPath;
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * 读取项目全部源码文件（带缓存），同一localPath只读一次
     */
    public String readAllSourceFilesCached(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return "";
        }
        return cache.computeIfAbsent(projectRoot, this::readAllSourceFiles);
    }

    /**
     * 清除缓存
     */
    public void evictCache(String projectRoot) {
        if (projectRoot != null) {
            cache.remove(projectRoot);
            keyFilesCache.remove(projectRoot);
        }
    }

    /**
     * 读取项目关键结构文件（带缓存）。
     * 仅包含项目定义文件、README、主配置、入口文件，用于目录生成。
     * 比全量源码小得多（~80K vs ~1M），大幅降低token消耗。
     */
    public String readKeyStructuralFilesCached(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return "";
        }
        return keyFilesCache.computeIfAbsent(projectRoot, this::readKeyStructuralFiles);
    }

    /**
     * 读取项目关键结构文件：项目定义文件、文档、核心配置、入口文件。
     * 用于目录生成场景，不需要全部源码，只需理解项目结构。
     */
    public String readKeyStructuralFiles(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return "";
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            log.warn("项目根目录不存在或非目录: {}", projectRoot);
            return "";
        }

        List<Path> allFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcludedPath(root, p))
                    .filter(p -> !isExcludedExtension(p))
                    .filter(p -> !isExcludedFileName(p))
                    .filter(p -> isKeyStructuralFile(root, p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("遍历项目目录失败: root={}, error={}", projectRoot, e.getMessage());
            return "";
        }

        allFiles.sort(filePriorityComparator(root));

        StringBuilder sb = new StringBuilder();
        int fileCount = 0;

        for (Path file : allFiles) {
            if (sb.length() >= KEY_FILES_MAX_TOTAL_CHARS) {
                log.info("readKeyStructuralFiles: 达到上限 {}K 字符，已读取 {} 个文件",
                        KEY_FILES_MAX_TOTAL_CHARS / 1000, fileCount);
                break;
            }

            String relativePath = root.relativize(file).toString().replace('\\', '/');
            String content = readFileContent(file);
            if (content == null || content.isBlank()) {
                continue;
            }

            boolean truncated = false;
            if (content.length() > KEY_FILES_MAX_SINGLE_CHARS) {
                content = content.substring(0, KEY_FILES_MAX_SINGLE_CHARS);
                truncated = true;
            }

            sb.append("=== FILE: ").append(relativePath).append(" ===\n");
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
            if (truncated) {
                sb.append("... [文件截断]\n");
            }
            sb.append("=== END FILE ===\n\n");
            fileCount++;
        }

        log.info("readKeyStructuralFiles: 完成预读，共 {} 个文件，总 {} 字符 (~{}K tokens)",
                fileCount, sb.length(), sb.length() / 4000);

        return sb.toString();
    }

    /**
     * 读取指定的文件列表（按相对路径）。
     * 用于文档生成场景，只读取该章节的dependent_files。
     */
    public String readSpecificFiles(String projectRoot, List<String> relativePaths) {
        if (projectRoot == null || projectRoot.isBlank() || relativePaths == null || relativePaths.isEmpty()) {
            return "";
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int fileCount = 0;
        int notFound = 0;

        for (String relPath : relativePaths) {
            if (sb.length() >= SPECIFIC_FILES_MAX_TOTAL_CHARS) {
                log.info("readSpecificFiles: 达到上限 {}K 字符，已读取 {} 个文件",
                        SPECIFIC_FILES_MAX_TOTAL_CHARS / 1000, fileCount);
                break;
            }

            if (relPath == null || relPath.isBlank()) {
                continue;
            }

            String cleanPath = relPath.trim().replace('\\', '/');
            Path file = root.resolve(cleanPath);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                notFound++;
                continue;
            }

            String content = readFileContent(file);
            if (content == null || content.isBlank()) {
                continue;
            }

            boolean truncated = false;
            if (content.length() > SPECIFIC_FILES_MAX_SINGLE_CHARS) {
                content = content.substring(0, SPECIFIC_FILES_MAX_SINGLE_CHARS);
                truncated = true;
            }

            sb.append("=== FILE: ").append(cleanPath).append(" ===\n");
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
            if (truncated) {
                sb.append("... [文件截断]\n");
            }
            sb.append("=== END FILE ===\n\n");
            fileCount++;
        }

        if (notFound > 0) {
            log.info("readSpecificFiles: {} 个文件未找到", notFound);
        }
        log.info("readSpecificFiles: 完成预读，共 {} 个文件，总 {} 字符 (~{}K tokens)",
                fileCount, sb.length(), sb.length() / 4000);

        return sb.toString();
    }

    /**
     * 读取项目全部源码文件，返回格式化字符串。
     * 优先级：核心源码 > 配置文件 > 文档 > 其他
     *
     * 输出格式：
     * === FILE: path/to/File.java ===
     * <文件内容>
     * === END FILE ===
     */
    public String readAllSourceFiles(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return "";
        }

        Path root = Paths.get(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            log.warn("项目根目录不存在或非目录: {}", projectRoot);
            return "";
        }

        // 1. 收集所有合格文件
        List<Path> allFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcludedPath(root, p))
                    .filter(p -> !isExcludedExtension(p))
                    .filter(p -> !isExcludedFileName(p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("遍历项目目录失败: root={}, error={}", projectRoot, e.getMessage());
            return "";
        }

        log.info("CodebasePreReader: 扫描到 {} 个合格文件, projectRoot={}", allFiles.size(), projectRoot);

        // 2. 按优先级排序
        allFiles.sort(filePriorityComparator(root));

        // 3. 限制文件数量
        if (allFiles.size() > MAX_FILES) {
            allFiles = new ArrayList<>(allFiles.subList(0, MAX_FILES));
        }

        // 4. 读取并拼接
        StringBuilder sb = new StringBuilder();
        int fileCount = 0;

        for (Path file : allFiles) {
            if (sb.length() >= MAX_TOTAL_CHARS) {
                log.info("CodebasePreReader: 达到总量上限 {}K 字符，已读取 {} 个文件",
                        MAX_TOTAL_CHARS / 1000, fileCount);
                break;
            }

            String relativePath = root.relativize(file).toString().replace('\\', '/');
            String content = readFileContent(file);
            if (content == null || content.isBlank()) {
                continue;
            }

            // 截断过大的单文件
            boolean truncated = false;
            if (content.length() > MAX_SINGLE_FILE_CHARS) {
                content = content.substring(0, MAX_SINGLE_FILE_CHARS);
                truncated = true;
            }

            sb.append("=== FILE: ").append(relativePath).append(" ===\n");
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
            if (truncated) {
                sb.append("... [文件截断，原始大小超过 ").append(MAX_SINGLE_FILE_CHARS).append(" 字符]\n");
            }
            sb.append("=== END FILE ===\n\n");
            fileCount++;
        }

        log.info("CodebasePreReader: 完成预读，共 {} 个文件，总 {} 字符 (~{}K tokens)",
                fileCount, sb.length(), sb.length() / 4000);

        return sb.toString();
    }

    private boolean isExcludedPath(Path root, Path file) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount(); i++) {
            String segment = relative.getName(i).toString().toLowerCase(Locale.ROOT);
            if (EXCLUDED_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedExtension(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXCLUDED_EXTENSIONS.contains(ext);
    }

    private boolean isExcludedFileName(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        return EXCLUDED_FILE_NAMES.contains(name);
    }

    /**
     * 判断是否为关键结构文件：项目定义、文档、核心配置、入口文件
     */
    private boolean isKeyStructuralFile(Path root, Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        String nameLower = name.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

        // 项目定义文件
        if ("pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name)
                || "package.json".equals(name) || "Cargo.toml".equals(name) || "go.mod".equals(name)) {
            return true;
        }

        // 文档文件
        if (DOC_FILE_NAMES.contains(name)) {
            return true;
        }

        // 核心配置文件
        if (CONFIG_EXTENSIONS.contains(ext)) {
            return true;
        }

        // SQL schema文件
        if ("sql".equals(ext)) {
            return true;
        }

        // 入口文件 / 主要接口文件（按名称模式匹配）
        if (nameLower.contains("application") || nameLower.contains("main")
                || nameLower.contains("app.") || nameLower.contains("index.")
                || nameLower.contains("bootstrap") || nameLower.contains("startup")) {
            return true;
        }

        // 路由/控制器（帮助理解项目结构）
        String relPath = root.relativize(file).toString().toLowerCase(Locale.ROOT);
        if (relPath.contains("controller") || relPath.contains("router")
                || relPath.contains("route") || relPath.contains("api")) {
            return true;
        }

        return false;
    }

    private int getFilePriority(Path root, Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        String ext = (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

        // 优先级0: pom.xml / build.gradle / package.json (项目定义文件)
        if ("pom.xml".equals(name) || "build.gradle".equals(name) || "build.gradle.kts".equals(name)
                || "package.json".equals(name) || "Cargo.toml".equals(name) || "go.mod".equals(name)) {
            return 0;
        }

        // 优先级1: 文档文件
        if (DOC_FILE_NAMES.contains(name)) {
            return 1;
        }

        // 优先级2: 核心源码
        if (CORE_SOURCE_EXTENSIONS.contains(ext)) {
            return 2;
        }

        // 优先级3: 配置文件
        if (CONFIG_EXTENSIONS.contains(ext)) {
            return 3;
        }

        // 优先级4: SQL等数据定义
        if ("sql".equals(ext) || "graphql".equals(ext) || "proto".equals(ext)) {
            return 4;
        }

        // 优先级5: 其他文本文件 (md, txt, sh, bat etc.)
        return 5;
    }

    private Comparator<Path> filePriorityComparator(Path root) {
        return Comparator
                .comparingInt((Path p) -> getFilePriority(root, p))
                .thenComparingInt(p -> p.getNameCount())
                .thenComparing(p -> root.relativize(p).toString());
    }

    private String readFileContent(Path file) {
        try {
            long size = Files.size(file);
            // 跳过大于500KB的文件（可能是生成/压缩文件）
            if (size > 500 * 1024) {
                return null;
            }
            // 跳过空文件
            if (size == 0) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            // 简单二进制检测：前512字节中如果有超过10%的不可打印字符，认为是二进制
            int checkLen = Math.min(bytes.length, 512);
            int nonPrintable = 0;
            for (int i = 0; i < checkLen; i++) {
                byte b = bytes[i];
                if (b == 0 || (b < 32 && b != '\n' && b != '\r' && b != '\t')) {
                    nonPrintable++;
                }
            }
            if (checkLen > 0 && (double) nonPrintable / checkLen > 0.1) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("读取文件失败: {}, error={}", file, e.getMessage());
            return null;
        }
    }
}
