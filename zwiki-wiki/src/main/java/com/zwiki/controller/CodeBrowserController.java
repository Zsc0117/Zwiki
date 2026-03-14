package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.domain.dto.FileTreeNode;
import com.zwiki.domain.dto.SymbolAnnotation;
import com.zwiki.domain.dto.SymbolReference;
import com.zwiki.repository.entity.Task;
import com.zwiki.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author pai
 * @description: 代码浏览器控制器
 * @date 2026/1/25
 */
@Slf4j
@RestController
@RequestMapping("/api/task/repo")
@RequiredArgsConstructor
public class CodeBrowserController {

    private final TaskService taskService;

    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_TREE_DEPTH = 10;
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "class", "jar", "war", "zip", "gz", "tar", "rar", "7z",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
            "mp3", "mp4", "avi", "mov", "wav", "flac",
            "exe", "dll", "so", "dylib", "o", "a",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "woff", "woff2", "ttf", "eot", "otf"
    );
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".settings", ".gradle",
            "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".next", ".nuxt", "vendor"
    );

    @GetMapping("/tree")
    public ResultVo<List<FileTreeNode>> getFileTree(@RequestParam("taskId") String taskId) {
        Task task = taskService.getTaskByTaskId(taskId);
        if (task == null) {
            return ResultVo.error(404, "任务不存在");
        }

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) {
            return ResultVo.error(400, "项目路径不存在");
        }

        File root = new File(projectPath);
        if (!root.exists() || !root.isDirectory()) {
            return ResultVo.error(400, "项目目录不存在或不是文件夹");
        }

        List<String> ignorePatterns = loadGitignore(root);
        List<FileTreeNode> tree = buildTree(root, root.getAbsolutePath(), ignorePatterns, 0);
        return ResultVo.success(tree);
    }

    @GetMapping(value = "/file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResultVo<String> getFileContent(
            @RequestParam("taskId") String taskId,
            @RequestParam("path") String path) {
        Task task = taskService.getTaskByTaskId(taskId);
        if (task == null) {
            return ResultVo.error(404, "任务不存在");
        }

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) {
            return ResultVo.error(400, "项目路径不存在");
        }

        // Sanitize path to prevent directory traversal
        String sanitized = path.replace("\\", "/");
        if (sanitized.contains("..") || sanitized.startsWith("/")) {
            return ResultVo.error(400, "非法的文件路径");
        }

        Path filePath = Paths.get(projectPath, sanitized).normalize();
        Path rootPath = Paths.get(projectPath).normalize();

        // Ensure the resolved path is within the project directory
        if (!filePath.startsWith(rootPath)) {
            return ResultVo.error(400, "非法的文件路径");
        }

        File file = filePath.toFile();
        if (!file.exists()) {
            return ResultVo.error(404, "文件不存在");
        }
        if (!file.isFile()) {
            return ResultVo.error(400, "路径不是文件");
        }
        if (file.length() > MAX_FILE_SIZE) {
            return ResultVo.error(400, "文件过大（超过1MB），无法预览");
        }

        String ext = getExtension(file.getName());
        if (BINARY_EXTENSIONS.contains(ext)) {
            return ResultVo.error(400, "二进制文件无法预览");
        }

        try {
            String content = Files.readString(filePath);
            return ResultVo.success(content);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return ResultVo.error(500, "读取文件失败");
        }
    }

    private List<FileTreeNode> buildTree(File dir, String rootPath, List<String> ignorePatterns, int depth) {
        if (depth > MAX_TREE_DEPTH) {
            return Collections.emptyList();
        }

        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return Collections.emptyList();
        }

        // Sort: directories first, then files, alphabetically
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<FileTreeNode> nodes = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();

            // Skip known non-project directories
            if (child.isDirectory() && SKIP_DIRS.contains(name)) continue;

            // Check gitignore
            String relativePath = getRelativePath(child, rootPath);
            if (isIgnored(relativePath, ignorePatterns, child.isDirectory())) continue;

            if (child.isDirectory()) {
                List<FileTreeNode> subChildren = buildTree(child, rootPath, ignorePatterns, depth + 1);
                nodes.add(FileTreeNode.builder()
                        .name(name)
                        .path(relativePath)
                        .type("directory")
                        .children(subChildren)
                        .build());
            } else {
                nodes.add(FileTreeNode.builder()
                        .name(name)
                        .path(relativePath)
                        .type("file")
                        .size(child.length())
                        .build());
            }
        }
        return nodes;
    }

    private String getRelativePath(File file, String rootPath) {
        String absPath = file.getAbsolutePath();
        if (absPath.startsWith(rootPath)) {
            String rel = absPath.substring(rootPath.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return rel.replace("\\", "/");
        }
        return file.getName();
    }

    private List<String> loadGitignore(File root) {
        File gitignore = new File(root, ".gitignore");
        List<String> patterns = new ArrayList<>();
        if (gitignore.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(gitignore))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        patterns.add(line);
                    }
                }
            } catch (IOException e) {
                log.warn("读取.gitignore失败: {}", e.getMessage());
            }
        }
        return patterns;
    }

    private boolean isIgnored(String relativePath, List<String> patterns, boolean isDirectory) {
        for (String pattern : patterns) {
            if (pattern.endsWith("/")) {
                if (isDirectory) {
                    String dirPattern = pattern.substring(0, pattern.length() - 1);
                    if (matchesPattern(relativePath, dirPattern)) return true;
                }
            } else if (pattern.contains("*")) {
                String fileName = relativePath.contains("/")
                        ? relativePath.substring(relativePath.lastIndexOf("/") + 1)
                        : relativePath;
                if (pattern.startsWith("*")) {
                    if (matchesPattern(fileName, pattern)) return true;
                } else {
                    if (matchesPattern(relativePath, pattern)) return true;
                }
            } else {
                if (relativePath.equals(pattern) || relativePath.startsWith(pattern + "/")) return true;
            }
        }
        return false;
    }

    private boolean matchesPattern(String text, String pattern) {
        String regex = "^" + pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
        try {
            return text.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    // ==================== Symbol Annotations & References ====================

    private static final int MAX_ANNOTATIONS_PER_FILE = 200;
    private static final int MAX_REFERENCES = 100;
    private static final int MAX_REFERENCES_PER_FILE = 20;
    private static final long MAX_SCAN_FILE_SIZE = 512 * 1024; // 512KB for scanning

    // --- Language-specific symbol definition patterns ---
    // Java: methods, constructors, fields, classes, interfaces, enums
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "(?:public|private|protected|static|final|abstract|synchronized|native|default|\\s)+" +
            "[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "(?:public|private|protected|static|final|abstract)?\\s*(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile(
            "(?:public|private|protected|static|final|volatile|transient|\\s)+" +
            "[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*[;=]");
    // JS/TS: function declarations, class, const/let/var, arrow functions
    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile(
            "(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_CLASS_PATTERN = Pattern.compile(
            "(?:export\\s+)?class\\s+(\\w+)");
    private static final Pattern JS_VAR_FUNC_PATTERN = Pattern.compile(
            "(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>");
    private static final Pattern JS_METHOD_PATTERN = Pattern.compile(
            "^\\s+(?:async\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*\\{");
    // Python: def, class
    private static final Pattern PY_DEF_PATTERN = Pattern.compile(
            "(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern PY_CLASS_PATTERN = Pattern.compile(
            "class\\s+(\\w+)");
    // Go: func
    private static final Pattern GO_FUNC_PATTERN = Pattern.compile(
            "func\\s+(?:\\([^)]*\\)\\s+)?(\\w+)\\s*\\(");

    @GetMapping("/symbol-annotations")
    public ResultVo<List<SymbolAnnotation>> getSymbolAnnotations(
            @RequestParam("taskId") String taskId,
            @RequestParam("path") String path) {

        Task task = taskService.getTaskByTaskId(taskId);
        if (task == null) return ResultVo.error(404, "任务不存在");

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) return ResultVo.error(400, "项目路径不存在");

        String sanitized = path.replace("\\", "/");
        if (sanitized.contains("..") || sanitized.startsWith("/"))
            return ResultVo.error(400, "非法的文件路径");

        Path filePath = Paths.get(projectPath, sanitized).normalize();
        Path rootPath = Paths.get(projectPath).normalize();
        if (!filePath.startsWith(rootPath)) return ResultVo.error(400, "非法的文件路径");

        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) return ResultVo.error(404, "文件不存在");

        try {
            String content = Files.readString(filePath);
            String ext = getExtension(file.getName());

            // 1. Parse symbol definitions from the file
            List<SymbolAnnotation> definitions = parseSymbolDefinitions(content, ext);
            if (definitions.isEmpty()) return ResultVo.success(Collections.emptyList());

            // Limit number of symbols to process
            if (definitions.size() > MAX_ANNOTATIONS_PER_FILE) {
                definitions = definitions.subList(0, MAX_ANNOTATIONS_PER_FILE);
            }

            // 2. Collect all searchable text files in the project
            List<String> ignorePatterns = loadGitignore(new File(projectPath));
            List<File> textFiles = collectTextFiles(new File(projectPath), projectPath, ignorePatterns, 0);

            // 3. For each symbol, count usages across the project
            for (SymbolAnnotation annotation : definitions) {
                int count = countSymbolUsages(annotation.getSymbol(), textFiles);
                annotation.setUsageCount(Math.max(0, count - 1)); // subtract the definition itself
            }

            // Filter out symbols with 0 usages to reduce noise
            List<SymbolAnnotation> result = definitions.stream()
                    .filter(a -> a.getUsageCount() > 0)
                    .collect(Collectors.toList());

            return ResultVo.success(result);
        } catch (IOException e) {
            log.error("解析 symbol annotations 失败: {}", filePath, e);
            return ResultVo.error(500, "解析失败");
        }
    }

    @GetMapping("/symbol-references")
    public ResultVo<List<SymbolReference>> getSymbolReferences(
            @RequestParam("taskId") String taskId,
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "defPath", required = false) String defPath,
            @RequestParam(value = "defLine", required = false, defaultValue = "0") int defLine) {

        Task task = taskService.getTaskByTaskId(taskId);
        if (task == null) return ResultVo.error(404, "任务不存在");

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) return ResultVo.error(400, "项目路径不存在");

        if (!StringUtils.hasText(symbol) || symbol.length() < 2) {
            return ResultVo.error(400, "符号名称太短");
        }

        try {
            List<String> ignorePatterns = loadGitignore(new File(projectPath));
            List<File> textFiles = collectTextFiles(new File(projectPath), projectPath, ignorePatterns, 0);

            Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
            List<SymbolReference> references = new ArrayList<>();

            for (File file : textFiles) {
                if (references.size() >= MAX_REFERENCES) break;
                if (file.length() > MAX_SCAN_FILE_SIZE) continue;

                try {
                    List<String> lines = Files.readAllLines(file.toPath());
                    int fileMatches = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        if (fileMatches >= MAX_REFERENCES_PER_FILE) break;
                        if (references.size() >= MAX_REFERENCES) break;

                        String line = lines.get(i);
                        if (wordPattern.matcher(line).find()) {
                            String relPath = getRelativePath(file, projectPath);
                            // Exclude the definition line itself
                            if (defPath != null && relPath.equals(defPath) && (i + 1) == defLine) {
                                continue;
                            }
                            references.add(SymbolReference.builder()
                                    .filePath(relPath)
                                    .lineNumber(i + 1)
                                    .lineContent(line.trim())
                                    .build());
                            fileMatches++;
                        }
                    }
                } catch (IOException e) {
                    // skip unreadable files
                }
            }

            return ResultVo.success(references);
        } catch (Exception e) {
            log.error("搜索 symbol references 失败: symbol={}", symbol, e);
            return ResultVo.error(500, "搜索失败");
        }
    }

    // ---- Symbol definition parsing ----

    private List<SymbolAnnotation> parseSymbolDefinitions(String content, String ext) {
        String lang = mapExtToLanguage(ext);
        String[] lines = content.split("\n");
        List<SymbolAnnotation> result = new ArrayList<>();
        Set<String> seen = new HashSet<>(); // avoid duplicate symbols on same line

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("#") || trimmed.startsWith("/*")) {
                continue;
            }

            List<SymbolAnnotation> lineSymbols = extractSymbolsFromLine(trimmed, lang, i + 1);
            for (SymbolAnnotation sym : lineSymbols) {
                String key = sym.getSymbol() + ":" + sym.getLine();
                if (!seen.contains(key) && isValidSymbolName(sym.getSymbol())) {
                    seen.add(key);
                    result.add(sym);
                }
            }
        }

        return result;
    }

    private List<SymbolAnnotation> extractSymbolsFromLine(String line, String lang, int lineNumber) {
        List<SymbolAnnotation> symbols = new ArrayList<>();

        switch (lang) {
            case "java" -> {
                // Class/interface/enum/record
                matchAndAdd(JAVA_CLASS_PATTERN, line, lineNumber, "CLASS", symbols);
                // Methods (only if line has a parenthesis — avoids matching fields)
                if (line.contains("(") && !line.matches(".*\\bnew\\s+\\w+.*")) {
                    matchAndAdd(JAVA_METHOD_PATTERN, line, lineNumber, "METHOD", symbols);
                }
                // Fields (private/protected/public final/static etc.)
                if ((line.contains(";") || line.contains("=")) && !line.contains("(")) {
                    matchAndAdd(JAVA_FIELD_PATTERN, line, lineNumber, "FIELD", symbols);
                }
            }
            case "javascript", "typescript" -> {
                matchAndAdd(JS_CLASS_PATTERN, line, lineNumber, "CLASS", symbols);
                matchAndAdd(JS_FUNCTION_PATTERN, line, lineNumber, "FUNCTION", symbols);
                matchAndAdd(JS_VAR_FUNC_PATTERN, line, lineNumber, "FUNCTION", symbols);
                matchAndAdd(JS_METHOD_PATTERN, line, lineNumber, "METHOD", symbols);
            }
            case "python" -> {
                matchAndAdd(PY_CLASS_PATTERN, line, lineNumber, "CLASS", symbols);
                matchAndAdd(PY_DEF_PATTERN, line, lineNumber, "FUNCTION", symbols);
            }
            case "go" -> {
                matchAndAdd(GO_FUNC_PATTERN, line, lineNumber, "FUNCTION", symbols);
            }
            default -> {
                // Generic: try all patterns
                matchAndAdd(JAVA_CLASS_PATTERN, line, lineNumber, "CLASS", symbols);
                if (line.contains("(")) {
                    matchAndAdd(JAVA_METHOD_PATTERN, line, lineNumber, "METHOD", symbols);
                    matchAndAdd(JS_FUNCTION_PATTERN, line, lineNumber, "FUNCTION", symbols);
                    matchAndAdd(PY_DEF_PATTERN, line, lineNumber, "FUNCTION", symbols);
                    matchAndAdd(GO_FUNC_PATTERN, line, lineNumber, "FUNCTION", symbols);
                }
            }
        }
        return symbols;
    }

    private void matchAndAdd(Pattern pattern, String line, int lineNumber, String kind,
                             List<SymbolAnnotation> symbols) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isEmpty()) {
                symbols.add(SymbolAnnotation.builder()
                        .symbol(name)
                        .line(lineNumber)
                        .kind(kind)
                        .usageCount(0)
                        .build());
            }
        }
    }

    private boolean isValidSymbolName(String name) {
        if (name == null || name.length() < 2 || name.length() > 60) return false;
        // Filter out common Java keywords / types that would produce too many false matches
        Set<String> skipWords = Set.of(
                "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
                "return", "try", "catch", "finally", "throw", "throws", "new", "this", "super",
                "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
                "String", "Object", "var", "val", "let", "const", "true", "false", "null",
                "public", "private", "protected", "static", "final", "abstract", "class",
                "interface", "enum", "extends", "implements", "import", "package", "def",
                "self", "None", "True", "False", "func", "type", "struct", "map", "list",
                "async", "await", "yield", "from", "with", "as", "is", "not", "and", "or",
                "export", "default", "function", "override", "virtual"
        );
        return !skipWords.contains(name);
    }

    private String mapExtToLanguage(String ext) {
        return switch (ext) {
            case "java", "kt" -> "java";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py", "pyw" -> "python";
            case "go" -> "go";
            default -> "unknown";
        };
    }

    // ---- File scanning utilities ----

    private List<File> collectTextFiles(File dir, String rootPath, List<String> ignorePatterns, int depth) {
        if (depth > MAX_TREE_DEPTH) return Collections.emptyList();

        File[] children = dir.listFiles();
        if (children == null) return Collections.emptyList();

        List<File> files = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (SKIP_DIRS.contains(name)) continue;
                String relPath = getRelativePath(child, rootPath);
                if (isIgnored(relPath, ignorePatterns, true)) continue;
                files.addAll(collectTextFiles(child, rootPath, ignorePatterns, depth + 1));
            } else {
                String ext = getExtension(name);
                if (BINARY_EXTENSIONS.contains(ext)) continue;
                String relPath = getRelativePath(child, rootPath);
                if (isIgnored(relPath, ignorePatterns, false)) continue;
                if (child.length() <= MAX_SCAN_FILE_SIZE) {
                    files.add(child);
                }
            }
        }
        return files;
    }

    private int countSymbolUsages(String symbol, List<File> files) {
        Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        int total = 0;
        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                Matcher m = wordPattern.matcher(content);
                while (m.find()) {
                    total++;
                }
            } catch (IOException e) {
                // skip
            }
        }
        return total;
    }
}
