package com.zwiki.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author pai
 * @description: 文件系统Tool
 * @date 2026/1/17 17:31
 */
@Service
@Slf4j
public class FileSystemTool {
    
    // 项目根路径 — 使用 AtomicReference 代替 ThreadLocal，因为工具回调在 reactor boundedElastic 线程执行，
    // 与设置根路径的虚拟线程不同，ThreadLocal 无法跨线程传递。
    private static final AtomicReference<String> PROJECT_ROOT = new AtomicReference<>();
    
    // 缓存最近读取的文件路径，避免重复读取（线程安全 Set）
    private static final Set<String> READ_FILE_CACHE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // 缓存最近的搜索参数，避免重复搜索（线程安全 Set）
    private static final Set<String> SEARCH_CACHE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    /**
     * 设置当前项目根路径
     */
    public static void setProjectRoot(String projectRoot) {
        if (projectRoot != null && !projectRoot.trim().isEmpty()) {
            PROJECT_ROOT.set(projectRoot.trim());
            // 清空文件读取缓存和搜索缓存
            READ_FILE_CACHE.clear();
            SEARCH_CACHE.clear();
            log.debug("设置项目根路径: {}", projectRoot);
        } else {
            log.warn("尝试设置空的项目根路径，已忽略");
        }
    }

    private boolean containsGlobPattern(String filePath) {
        if (filePath == null) {
            return false;
        }
        String t = filePath;
        return t.contains("*") || t.contains("?") || t.contains("[") || t.contains("]") || t.contains("{") || t.contains("}");
    }

    private String normalizeGlobPattern(String pattern) {
        return pattern.replace('\\', '/');
    }

    private File findFirstByGlobPattern(String globPattern) {
        String projectRoot = getProjectRoot();
        Path rootPath = Paths.get(projectRoot);
        String normalized = normalizeGlobPattern(globPattern);
        java.nio.file.PathMatcher matcher = rootPath.getFileSystem().getPathMatcher("glob:" + normalized);

        try (Stream<Path> stream = Files.walk(rootPath)) {
            List<Path> matches = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = rootPath.relativize(p).toString().replace('\\', '/');
                        return matcher.matches(Paths.get(rel));
                    })
                    .sorted(Comparator.comparingInt(p -> p.toString().length()))
                    .collect(Collectors.toList());

            if (!matches.isEmpty()) {
                return matches.get(0).toFile();
            }
        } catch (Exception e) {
            log.debug("glob匹配文件失败: pattern={}, error={}", globPattern, e.getMessage());
        }

        return resolveFile(globPattern);
    }
    
    /**
     * 清除当前项目根路径
     */
    public static void clearProjectRoot() {
        PROJECT_ROOT.set(null);
        READ_FILE_CACHE.clear();
        SEARCH_CACHE.clear();
        log.debug("清除项目根路径");
    }
    
    /**
     * 获取ThreadLocal状态信息（用于调试）
     */
    public static String getThreadLocalStatus() {
        String projectRoot = PROJECT_ROOT.get();
        return String.format("ProjectRoot状态: projectRoot=%s, readCacheSize=%d, searchCacheSize=%d", 
                projectRoot != null ? projectRoot : "null", READ_FILE_CACHE.size(), SEARCH_CACHE.size());
    }
    
    /**
     * 强制重置ThreadLocal（用于异常恢复）
     */
    public static void forceResetThreadLocal() {
        try {
            PROJECT_ROOT.set(null);
            READ_FILE_CACHE.clear();
            SEARCH_CACHE.clear();
            log.info("强制重置FileSystemTool状态");
        } catch (Exception e) {
            log.warn("强制重置时发生异常: {}", e.getMessage());
        }
    }
    
    /**
     * 获取当前项目根路径
     */
    private String getProjectRoot() {
        String root = PROJECT_ROOT.get();
        if (root == null || root.trim().isEmpty()) {
            log.warn("项目根路径未设置或为空，使用当前工作目录: {}", System.getProperty("user.dir"));
            return System.getProperty("user.dir");
        }
        log.debug("获取项目根路径: {}", root);
        return root;
    }
    
    /**
     * 检查项目根路径是否已设置
     */
    public static boolean isProjectRootSet() {
        String root = PROJECT_ROOT.get();
        return root != null && !root.trim().isEmpty();
    }
    
    /**
     * 解析文件路径，支持相对路径和绝对路径
     */
    private File resolveFile(String filePath) {
        Path path = Paths.get(filePath);
        
        if (path.isAbsolute()) {
            // 绝对路径直接使用
            return path.toFile();
        } else {
            // 相对路径需要拼接项目根路径
            String projectRoot = getProjectRoot();
            Path resolvedPath = Paths.get(projectRoot, filePath);
            log.debug("相对路径 {} 解析为绝对路径: {}", filePath, resolvedPath.toString());
            return resolvedPath.toFile();
        }
    }
    
    /**
     * 查找文件，支持模糊匹配
     */
    private File findFile(String filePath) {
        if (containsGlobPattern(filePath)) {
            File globFile = findFirstByGlobPattern(filePath);
            if (globFile != null && globFile.exists()) {
                log.info("找到匹配文件: {} -> {}", filePath, globFile.getAbsolutePath());
                return globFile;
            }
        }

        File file = resolveFile(filePath);
        
        // 如果文件存在，直接返回
        if (file.exists()) {
            return file;
        }
        
        // 如果文件不存在，尝试在项目根目录及其子目录中查找
        String projectRoot = getProjectRoot();
        File rootDir = new File(projectRoot);
        
        log.debug("文件 {} 不存在，尝试在项目根目录中查找...", file.getAbsolutePath());
        
        // 递归查找文件
        File foundFile = searchFile(rootDir, new File(filePath).getName());
        if (foundFile != null) {
            log.info("找到匹配文件: {} -> {}", filePath, foundFile.getAbsolutePath());
            return foundFile;
        }
        
        // 尝试使用文件名（不含扩展名）进行模糊匹配（优先匹配同扩展名）
        String fileName = new File(filePath).getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = fileName.substring(0, dotIndex);
            String extension = fileName.substring(dotIndex + 1);
            foundFile = searchFileByBaseName(rootDir, baseName, extension);
            if (foundFile != null) {
                log.info("通过模糊匹配找到文件: {} -> {}", filePath, foundFile.getAbsolutePath());
                return foundFile;
            }
        }
        
        // 如果还是找不到，返回原始文件对象
        return file;
    }
    
    /**
     * 递归搜索文件
     */
    private File searchFile(File directory, String fileName) {
        if (!directory.isDirectory()) {
            return null;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        
        // 首先在当前目录查找
        for (File file : files) {
            if (file.isFile() && file.getName().equals(fileName)) {
                return file;
            }
        }
        
        // 递归搜索子目录（限制深度避免过深搜索）
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                File found = searchFile(file, fileName);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }

    private List<String> findCandidates(File directory, String fileName, int limit) {
        if (directory == null || !directory.isDirectory() || limit <= 0) {
            return List.of();
        }

        String targetLower = fileName.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory.toPath())) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(targetLower))
                    .sorted(Comparator.comparingInt(p -> p.toString().length()))
                    .limit(limit)
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("查找候选文件失败: fileName={}, error={}", fileName, e.getMessage());
        }
        return candidates;
    }
    
    /**
     * 通过文件名（不含扩展名）模糊搜索文件
     */
    private File searchFileByBaseName(File directory, String baseName, String preferredExtension) {
        if (!directory.isDirectory()) {
            return null;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        
        // 首先在当前目录查找（优先匹配同扩展名）
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                int dotIndex = fileName.lastIndexOf('.');
                String fileBaseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
                String fileExt = dotIndex > 0 ? fileName.substring(dotIndex + 1) : "";
                if (fileBaseName.equals(baseName) && preferredExtension != null && !preferredExtension.isBlank() && preferredExtension.equalsIgnoreCase(fileExt)) {
                    return file;
                }
            }
        }

        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                int dotIndex = fileName.lastIndexOf('.');
                String fileBaseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
                if (fileBaseName.equals(baseName)) {
                    return file;
                }
            }
        }
        
        // 递归搜索子目录
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                File found = searchFileByBaseName(file, baseName, preferredExtension);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }
    /**
     * 读取指定文件路径的全部内容
     *
     * @param filePath 文件路径
     * @return 文件内容字符串
     */
    @Tool(name = "readFile", description = "Read the content of the specified file")
    public String readFile(@ToolParam(description = "file path") String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            log.warn("❌ 文件路径为空");
            return "错误：文件路径不能为空";
        }
        
        String normalizedPath = filePath.trim();
        
        // 检查项目根路径是否已设置
        if (!isProjectRootSet()) {
            log.warn("⚠️ 项目根路径未设置，可能导致文件读取失败");
        }
        
        // 检查是否最近已经读取过相同文件，避免重复处理
        String cacheKey = normalizedPath + "@" + getProjectRoot();
        
        if (READ_FILE_CACHE.contains(cacheKey)) {
            log.info("⚠️ 阻止重复读取文件: {} (项目根: {})", normalizedPath, getProjectRoot());
            return "[此文件已在之前的工具调用中读取过，内容与之前完全相同。请直接使用之前获取的文件内容，不要重复读取同一文件。]";
        } else {
            READ_FILE_CACHE.add(cacheKey);
        }
        
        StringBuilder content = new StringBuilder();
        
        // 使用改进的文件查找逻辑
        File file = findFile(normalizedPath);
        
        // 记录读取尝试
        log.info("🔍 尝试读取文件: {} (项目根: {})", normalizedPath, getProjectRoot());
        log.debug("📁 文件绝对路径: {}", file.getAbsolutePath());
        log.debug("✅ 文件是否存在: {}", file.exists());
        
        if (!file.exists()) {
            String errorMsg = String.format("文件不存在: %s (绝对路径: %s)", normalizedPath, file.getAbsolutePath());
            log.warn("❌ {}", errorMsg);
            File rootDir = new File(getProjectRoot());
            List<String> candidates = findCandidates(rootDir, new File(normalizedPath).getName(), 5);
            if (!candidates.isEmpty()) {
                return String.format("错误：文件不存在 - %s\n建议：请检查文件路径是否正确。可能的候选文件：\n%s", normalizedPath, String.join("\n", candidates));
            }
            return String.format("错误：文件不存在 - %s\n建议：请检查文件路径是否正确，或者该文件可能不在当前项目中。", normalizedPath);
        }
        
        // 如果是目录，列出目录内容而不是尝试读取
        if (file.isDirectory()) {
            log.info("📁 路径是目录，列出目录内容: {}", file.getAbsolutePath());
            StringBuilder dirContent = new StringBuilder();
            dirContent.append(String.format("目录: %s\n\n", normalizedPath));
            File[] children = file.listFiles();
            if (children != null && children.length > 0) {
                java.util.Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File child : children) {
                    if (child.getName().startsWith(".")) continue;
                    if (child.isDirectory()) {
                        dirContent.append(String.format("  📁 %s/\n", child.getName()));
                    } else {
                        dirContent.append(String.format("  📄 %s (%d bytes)\n", child.getName(), child.length()));
                    }
                }
            } else {
                dirContent.append("  (空目录)\n");
            }
            return dirContent.toString();
        }
        
        if (!file.canRead()) {
            String errorMsg = String.format("文件无读取权限: %s", normalizedPath);
            log.error("❌ {}", errorMsg);
            return String.format("错误：文件无读取权限 - %s", normalizedPath);
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            log.info("✅ 文件读取成功: {} (长度: {} 字符)", normalizedPath, content.length());
        } catch (IOException e) {
            String errorMsg = "读取文件失败: " + normalizedPath + ", 错误: " + e.getMessage();
            log.error("❌ {}", errorMsg, e);
            return String.format("错误：读取文件失败 - %s, 原因: %s", normalizedPath, e.getMessage());
        }
        return content.toString();
    }

    /**
     * 在项目文件中搜索包含指定关键词的内容（类似grep）
     *
     * @param keyword 要搜索的关键词
     * @param directory 搜索的子目录路径（相对于项目根目录），为空时搜索整个项目
     * @param fileExtension 限制搜索的文件扩展名（如 java, py, xml），为空时搜索所有文本文件
     * @return 匹配的文件路径和对应行内容
     */
    @Tool(name = "searchContent", description = "Search for a keyword across project files (like grep). Returns matching file paths and line contents.")
    public String searchContent(
            @ToolParam(description = "keyword to search for") String keyword,
            @ToolParam(description = "subdirectory to search in (relative to project root), empty for whole project") String directory,
            @ToolParam(description = "file extension filter (e.g. java, xml, py), empty for all text files") String fileExtension) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误：搜索关键词不能为空";
        }

        String projectRoot = getProjectRoot();
        Path searchRoot;
        if (directory != null && !directory.trim().isEmpty()) {
            searchRoot = Paths.get(projectRoot, directory.trim());
        } else {
            searchRoot = Paths.get(projectRoot);
        }

        if (!Files.exists(searchRoot) || !Files.isDirectory(searchRoot)) {
            return "错误：搜索目录不存在 - " + searchRoot;
        }

        String kw = keyword.trim();
        String kwLower = kw.toLowerCase(Locale.ROOT);
        String ext = (fileExtension != null && !fileExtension.trim().isEmpty())
                ? fileExtension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")
                : null;

        // 检查是否已经用相同参数搜索过
        String searchKey = kw + "|" + searchRoot + "|" + (ext != null ? ext : "");
        if (SEARCH_CACHE.contains(searchKey)) {
            log.info("⚠️ 阻止重复搜索: keyword='{}', dir={}, ext={}", kw, searchRoot, ext);
            return "[此搜索已在之前执行过，结果与之前完全相同。请使用不同的关键词搜索，或直接基于已有信息回答。]";
        }
        SEARCH_CACHE.add(searchKey);

        log.info("🔍 搜索关键词: '{}', 目录: {}, 扩展名: {}", kw, searchRoot, ext != null ? ext : "全部");

        int maxResults = 30;
        int maxLineLength = 200;
        StringBuilder result = new StringBuilder();
        int matchCount = 0;
        int filesScanned = 0;

        try (Stream<Path> stream = Files.walk(searchRoot)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        // 跳过隐藏文件和常见非文本文件
                        if (name.startsWith(".")) return false;
                        String nameLower = name.toLowerCase(Locale.ROOT);
                        if (nameLower.endsWith(".class") || nameLower.endsWith(".jar") ||
                                nameLower.endsWith(".war") || nameLower.endsWith(".zip") ||
                                nameLower.endsWith(".gz") || nameLower.endsWith(".png") ||
                                nameLower.endsWith(".jpg") || nameLower.endsWith(".gif") ||
                                nameLower.endsWith(".ico") || nameLower.endsWith(".exe") ||
                                nameLower.endsWith(".dll") || nameLower.endsWith(".so") ||
                                nameLower.endsWith(".node_modules")) return false;
                        if (ext != null) {
                            return nameLower.endsWith("." + ext);
                        }
                        return true;
                    })
                    .filter(p -> {
                        // 跳过隐藏目录和常见非项目目录
                        String rel = searchRoot.relativize(p).toString().replace('\\', '/');
                        return !rel.contains("/.")
                                && !rel.contains("node_modules/")
                                && !rel.contains("target/")
                                && !rel.contains("/build/")
                                && !rel.contains("/.git/");
                    })
                    .collect(Collectors.toList());

            for (Path filePath : files) {
                if (matchCount >= maxResults) break;
                filesScanned++;
                try {
                    // 跳过大文件（>1MB）
                    if (Files.size(filePath) > 1024 * 1024) continue;

                    List<String> lines = Files.readAllLines(filePath);
                    Path relPath = Paths.get(projectRoot).relativize(filePath);
                    boolean fileHeaderPrinted = false;

                    for (int i = 0; i < lines.size() && matchCount < maxResults; i++) {
                        String line = lines.get(i);
                        if (line.toLowerCase(Locale.ROOT).contains(kwLower)) {
                            if (!fileHeaderPrinted) {
                                result.append("\n📄 ").append(relPath.toString().replace('\\', '/')).append("\n");
                                fileHeaderPrinted = true;
                            }
                            String displayLine = line.trim();
                            if (displayLine.length() > maxLineLength) {
                                displayLine = displayLine.substring(0, maxLineLength) + "...";
                            }
                            result.append("  L").append(i + 1).append(": ").append(displayLine).append("\n");
                            matchCount++;
                        }
                    }
                } catch (Exception e) {
                    // 跳过无法读取的文件（如二进制文件）
                }
            }
        } catch (IOException e) {
            log.error("搜索文件失败: {}", e.getMessage(), e);
            return "错误：搜索失败 - " + e.getMessage();
        }

        if (matchCount == 0) {
            return String.format("未找到包含 '%s' 的内容（已扫描 %d 个文件）", kw, filesScanned);
        }

        String header = String.format("搜索 '%s' 的结果（共 %d 条匹配，扫描了 %d 个文件）：\n", kw, matchCount, filesScanned);
        if (matchCount >= maxResults) {
            header += "（结果已截断，请缩小搜索范围以获取更多结果）\n";
        }
        return header + result;
    }

    /**
     * 列出项目目录树结构（递归，可指定深度）
     *
     * @param directory 要列出的目录路径（相对于项目根目录），为空时列出项目根目录
     * @param maxDepth 最大递归深度，默认3层
     * @return 目录树结构字符串
     */
    @Tool(name = "listTree", description = "List the project directory tree recursively up to a specified depth. Useful for understanding the project structure.")
    public String listTree(
            @ToolParam(description = "directory path relative to project root, empty for root") String directory,
            @ToolParam(description = "max depth (1-5), default 3") int maxDepth) {
        String projectRoot = getProjectRoot();
        Path treeRoot;
        if (directory != null && !directory.trim().isEmpty()) {
            treeRoot = Paths.get(projectRoot, directory.trim());
        } else {
            treeRoot = Paths.get(projectRoot);
        }

        if (!Files.exists(treeRoot) || !Files.isDirectory(treeRoot)) {
            return "错误：目录不存在 - " + treeRoot;
        }

        int depth = maxDepth;
        if (depth < 1) depth = 3;
        if (depth > 5) depth = 5;

        log.info("📂 列出目录树: {}, 深度: {}", treeRoot, depth);

        StringBuilder sb = new StringBuilder();
        Path rootBase = Paths.get(projectRoot);
        String rootLabel = treeRoot.equals(rootBase)
                ? treeRoot.getFileName().toString() + "/"
                : rootBase.relativize(treeRoot).toString().replace('\\', '/') + "/";
        sb.append(rootLabel).append("\n");
        buildTree(treeRoot, "", depth, sb, 0);

        return sb.toString();
    }

    private void buildTree(Path dir, String prefix, int maxDepth, StringBuilder sb, int currentDepth) {
        if (currentDepth >= maxDepth) return;

        File[] children = dir.toFile().listFiles();
        if (children == null || children.length == 0) return;

        // 排序：目录在前，文件在后，各自按名称排序
        java.util.Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        // 过滤隐藏文件和常见非项目目录
        List<File> filtered = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                if (name.equals("node_modules") || name.equals("target") || name.equals("build")
                        || name.equals(".git") || name.equals("__pycache__") || name.equals(".idea")) continue;
            }
            filtered.add(child);
        }

        for (int i = 0; i < filtered.size(); i++) {
            File child = filtered.get(i);
            boolean isLast = (i == filtered.size() - 1);
            String connector = isLast ? "└── " : "├── ";
            String childPrefix = isLast ? "    " : "│   ";

            if (child.isDirectory()) {
                sb.append(prefix).append(connector).append(child.getName()).append("/\n");
                buildTree(child.toPath(), prefix + childPrefix, maxDepth, sb, currentDepth + 1);
            } else {
                sb.append(prefix).append(connector).append(child.getName()).append("\n");
            }
        }
    }

    /**
     * 读取指定文件路径的指定行区间内容（包含startLine和endLine，行号从1开始）
     * @param filePath 文件路径
     * @param startLine 起始行号（从1开始）
     * @param endLine 结束行号（从1开始）
     * @return 指定区间的内容字符串
     */
    @Tool(name = "readFileLines", description = "Read the content of the specified file within a specified line range")
    public String readFileLines(@ToolParam(description = "file path") String filePath, @ToolParam(description = "start line") int startLine, @ToolParam(description = "end line") int endLine) {
        if (filePath == null || filePath.trim().isEmpty()) {
            log.warn("❌ 文件路径为空");
            return "错误：文件路径不能为空";
        }

        int normalizedStart = startLine;
        int normalizedEnd = endLine;

        // 兼容模型可能使用0-based行号（如 startLine=0, endLine=30）
        if (normalizedStart == 0) {
            normalizedStart = 1;
            if (normalizedEnd >= 0) {
                normalizedEnd = normalizedEnd + 1;
            }
        }

        if (normalizedStart < 1) {
            normalizedStart = 1;
        }
        if (normalizedEnd < 1) {
            normalizedEnd = normalizedStart;
        }
        if (normalizedEnd < normalizedStart) {
            normalizedEnd = normalizedStart;
        }

        String normalizedPath = filePath.trim();

        if (!isProjectRootSet()) {
            log.warn("⚠️ 项目根路径未设置，可能导致文件读取失败");
        }

        File file = findFile(normalizedPath);
        log.info("🔍 尝试读取文件行区间: {} ({}-{}, 项目根: {})", normalizedPath, normalizedStart, normalizedEnd, getProjectRoot());

        if (!file.exists()) {
            log.warn("❌ 文件不存在: {} (绝对路径: {})", normalizedPath, file.getAbsolutePath());
            File rootDir = new File(getProjectRoot());
            List<String> candidates = findCandidates(rootDir, new File(normalizedPath).getName(), 5);
            if (!candidates.isEmpty()) {
                return String.format("错误：文件不存在 - %s\n建议：请检查文件路径是否正确。可能的候选文件：\n%s", normalizedPath, String.join("\n", candidates));
            }
            return String.format("错误：文件不存在 - %s\n建议：请检查文件路径是否正确。", normalizedPath);
        }
        if (!file.canRead()) {
            log.error("❌ 文件无读取权限: {}", normalizedPath);
            return String.format("错误：文件无读取权限 - %s", normalizedPath);
        }

        StringBuilder content = new StringBuilder();
        int totalLines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int currentLine = 1;
            while ((line = reader.readLine()) != null) {
                totalLines = currentLine;
                if (currentLine >= normalizedStart && currentLine <= normalizedEnd) {
                    content.append(line).append("\n");
                }
                if (currentLine > normalizedEnd) {
                    break;
                }
                currentLine++;
            }
        } catch (IOException e) {
            log.error("❌ 读取文件指定行失败: {}, error={}", normalizedPath, e.getMessage(), e);
            return String.format("错误：读取文件指定行失败 - %s, 原因: %s", normalizedPath, e.getMessage());
        }

        if (content.length() == 0 && totalLines > 0 && normalizedStart > totalLines) {
            log.warn("❌ 行号超出文件范围: requested=({}-{}), totalLines={}, filePath={}", normalizedStart, normalizedEnd, totalLines, normalizedPath);
            return String.format("错误：行号超出文件范围 startLine=%d, endLine=%d, totalLines=%d", normalizedStart, normalizedEnd, totalLines);
        }

        return content.toString();
    }

}
