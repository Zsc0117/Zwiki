package com.zwiki.service;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author pai
 * @description: 文件服务实现类
 * @date 2026/1/20 19:23
 */
@Slf4j
@Service
public class FileService {

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "target", ".git", ".idea", ".svn",
            "dist", "build", ".gradle", "__pycache__", ".venv", "venv"
    );

    @Value("${project.repository.base-path:./repository}")
    private String repositoryBasePath;

    @Value("${project.repository.file-tree.max-depth:10}")
    private int fileTreeMaxDepth;

    @Value("${project.repository.file-tree.max-entries:8000}")
    private int fileTreeMaxEntries;

    public String getFileTree(String localPath) {
        //1.读取gitignore文件
        File gitignoreFile = new File(localPath, ".gitignore");
        List<String> ignorePatterns = new ArrayList<>();
        if (gitignoreFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(gitignoreFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        ignorePatterns.add(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("读取.gitignore文件失败" + e.getMessage(), e);
            }
        }
        // 2. 递归读取localPath下的所有文件，包括文件夹，并过滤.gitignore内包含的文件
        StringBuilder mdTree = new StringBuilder();
        AtomicInteger entryCounter = new AtomicInteger(0);
        buildFileTree(new File(localPath), "", mdTree, ignorePatterns, localPath, 0, entryCounter);
        // 3. 直接返回md格式内容
        return mdTree.toString();
    }

    public String unzipToProjectDir(MultipartFile file, String userName, String projectName) {
        log.info("开始解压文件，文件名：{}，大小：{} bytes", file.getOriginalFilename(), file.getSize());
        String baseDir = getAbsoluteRepositoryPath();
        String destDir = baseDir + File.separator + userName + File.separator + projectName;
        log.info("解压目录：{}", destDir);

        File destDirFile = new File(destDir);
        if (!destDirFile.exists()) {
            destDirFile.mkdirs();
            log.info("创建目录：{}", destDir);
        }
        // 先保存到临时文件，以便在UTF-8失败时用GBK重试
        File tempFile = null;
        try {
            tempFile = File.createTempFile("zwiki-upload-", ".zip");
            file.transferTo(tempFile);

            try {
                doUnzip(tempFile, destDir, StandardCharsets.UTF_8);
            } catch (Exception e) {
                if (isCharsetError(e)) {
                    log.warn("UTF-8解压失败，尝试GBK编码重试");
                    // 清理已解压的部分文件
                    File destDirForClean = new File(destDir);
                    if (destDirForClean.exists()) {
                        FileUtils.deleteDirectory(destDirForClean);
                        destDirForClean.mkdirs();
                    }
                    doUnzip(tempFile, destDir, Charset.forName("GBK"));
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("解压文件时发生错误", e);
            throw new RuntimeException("解压文件时发生错误: " + e.getMessage(), e);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        return destDir;
    }

    public String unzipToProjectDir(File tempZipFile, String userName, String projectName) {
        log.info("开始解压临时文件，大小：{} bytes", tempZipFile.length());
        String baseDir = getAbsoluteRepositoryPath();
        String destDir = baseDir + File.separator + userName + File.separator + projectName;
        log.info("解压目录：{}", destDir);

        File destDirFile = new File(destDir);
        if (!destDirFile.exists()) {
            destDirFile.mkdirs();
        }
        try {
            try {
                doUnzip(tempZipFile, destDir, StandardCharsets.UTF_8);
            } catch (Exception e) {
                if (isCharsetError(e)) {
                    log.warn("UTF-8解压失败，尝试GBK编码重试");
                    File destDirForClean = new File(destDir);
                    if (destDirForClean.exists()) {
                        FileUtils.deleteDirectory(destDirForClean);
                        destDirForClean.mkdirs();
                    }
                    doUnzip(tempZipFile, destDir, Charset.forName("GBK"));
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("解压文件时发生错误", e);
            throw new RuntimeException("解压文件时发生错误: " + e.getMessage(), e);
        }
        return destDir;
    }

    public String getRepositoryPath(String userName, String projectName) {
        String baseDir = getAbsoluteRepositoryPath();
        String localPath = baseDir + File.separator + userName + File.separator + projectName;
        File baseDirFile = new File(baseDir);
        if (!baseDirFile.exists()) {
            baseDirFile.mkdirs();
        }
        //如果localpath已存在，则删除/projectName目录
        File projectDir = new File(localPath);
        if (projectDir.exists()) {
            try {
                log.info("项目目录 {} 已存在，正在删除...", localPath);
                FileUtils.deleteDirectory(projectDir);
                log.info("项目目录 {} 删除成功", localPath);
            } catch (IOException e) {
                log.error("删除项目目录 {} 失败: {}", localPath, e.getMessage(), e);
                throw new RuntimeException("删除已存在的项目目录失败: " + e.getMessage(), e);
            }
        }

        // 确保目录存在
        projectDir.mkdirs();
        return localPath;
    }

    public void deleteProjectDirectory(String userName, String projectName) {
        if (userName == null || projectName == null) {
            log.warn("无法删除项目目录，用户名或项目名为空");
            return;
        }
        String baseDir = getAbsoluteRepositoryPath();
        String projectPath = baseDir + File.separator + userName + File.separator + projectName;
        File projectDir = new File(projectPath);

        if (projectDir.exists()) {
            try {
                log.info("正在删除项目目录：{}", projectPath);
                FileUtils.deleteDirectory(projectDir);
                log.info("项目目录删除成功：{}", projectPath);
            } catch (IOException e) {
                log.error("项目目录{}删除失败：{}", projectPath, e.getMessage(), e);
                throw new RuntimeException("项目目录删除失败：" + e.getMessage(), e);
            }
        } else {
            log.info("项目目录{}不存在,无需删除", projectPath);
        }

    }

    /**
     * 获取仓库存储的绝对路径
     * 支持相对路径和绝对路径配置
     */
    private String getAbsoluteRepositoryPath() {
        File repoPath = new File(repositoryBasePath);
        if (repoPath.isAbsolute()) {
            return repositoryBasePath;
        } else {
            // 相对路径，相对于工作目录
            return System.getProperty("user.dir") + File.separator + repositoryBasePath;
        }
    }

    /**
     * 构建文件树
     *
     * @param dir            文件夹
     * @param prefix         前缀
     * @param mdTree         md树
     * @param ignorePatterns 忽略模式
     * @param rootPath       根路径
     */
    private void buildFileTree(File dir, String prefix, StringBuilder mdTree, List<String> ignorePatterns, String rootPath, int depth, AtomicInteger entryCounter) {

        String name = dir.getName();
        if (!dir.exists() || name.startsWith(".") || isIgnored(dir, ignorePatterns, rootPath)) {
            return;
        }

        if (depth > fileTreeMaxDepth) {
            return;
        }

        if (entryCounter.get() >= fileTreeMaxEntries) {
            return;
        }

        if (!dir.getAbsolutePath().equals(rootPath)) {
            if (dir.isDirectory()) {
                mdTree.append(prefix).append("- ").append(name).append("/").append("\n");
            } else {
                mdTree.append(prefix).append("- ").append(name).append("\n");
            }
            entryCounter.incrementAndGet();
        }
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File file : files) {
                    if (entryCounter.get() >= fileTreeMaxEntries) {
                        break;
                    }
                    buildFileTree(file, prefix + "  ", mdTree, ignorePatterns, rootPath, depth + 1, entryCounter);
                }
            }
        }
    }

    /**
     * 判断文件是否被忽略
     *
     * @param file           文件
     * @param ignorePatterns 忽略模式
     * @param rootPath       根路径
     * @return 是否被忽略
     */
    private boolean isIgnored(File file, List<String> ignorePatterns, String rootPath) {

        // 如果文件是根目录本身，则不应被.gitignore模式忽略
        if (file.getAbsolutePath().equals(rootPath)) {
            return false;
        }

        // 确保文件路径确实是rootPath的子路径，避免IndexOutOfBoundsException
        if (!file.getAbsolutePath().startsWith(rootPath + File.separator)) {
            // 如果文件不在rootPath下，也视为不忽略（或根据实际业务调整）
            return false;
        }
        String relativePath = file.getAbsolutePath().substring(rootPath.length() + 1).replace("\\", "/");

        for (String pattern : ignorePatterns) {
            if (matchesGitignorePattern(relativePath, pattern, file.isDirectory())) {
                return true;
            }
        }
        return false;

    }

    /**
     * 检查文件路径是否匹配gitignore模式
     *
     * @param relativePath 相对路径
     * @param pattern      gitignore模式
     * @param isDirectory  是否为目录
     * @return 是否匹配
     */
    private boolean matchesGitignorePattern(String relativePath, String pattern, boolean isDirectory) {
        // 处理目录模式（以/结尾）
        if (pattern.endsWith("/")) {
            if (!isDirectory) {
                return false; // 目录模式不匹配文件
            }
            String dirPattern = pattern.substring(0, pattern.length() - 1);
            return matchesWildcardPattern(relativePath, dirPattern);
        }

        // 处理文件名模式（包含通配符）
        if (pattern.contains("*")) {
            // 对于*.log这样的模式，匹配文件名
            if (pattern.startsWith("*")) {
                String fileName = relativePath.contains("/") ?
                        relativePath.substring(relativePath.lastIndexOf("/") + 1) : relativePath;
                return matchesWildcardPattern(fileName, pattern);
            }
            // 对于path/*.ext这样的模式
            return matchesWildcardPattern(relativePath, pattern);
        }

        // 精确匹配
        return relativePath.equals(pattern) || relativePath.startsWith(pattern + "/");
    }

    /**
     * 通配符模式匹配
     *
     * @param text    要匹配的文本
     * @param pattern 包含*通配符的模式
     * @return 是否匹配
     */
    private boolean matchesWildcardPattern(String text, String pattern) {
        // 将通配符模式转换为正则表达式
        String regex = "^" + pattern.replace("*", ".*").replace("?", ".") + "$";
        return text.matches(regex);
    }

    /**
     * 创建文件
     *
     * @param destinationDir 目标目录
     * @param entryName      条目名称
     * @return 创建的文件
     * @throws IOException 创建文件时发生IO异常
     */
    private File newFile(String destinationDir, String entryName) throws IOException {
        File destFile = new File(destinationDir, entryName);
        String destDirPath = new File(destinationDir).getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("压缩文件中包含着非法的文件路径：" + entryName);
        }
        return destFile;
    }

    /**
     * 使用指定字符集解压ZIP文件
     */
    private void doUnzip(File zipFile, String destDir, Charset charset) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile), charset)) {
            ZipEntry zipEntry;
            int fileCount = 0;
            int skippedCount = 0;
            while ((zipEntry = zip.getNextEntry()) != null) {
                String entryName = zipEntry.getName();
                if (shouldSkipEntry(entryName)) {
                    skippedCount++;
                    zip.closeEntry();
                    continue;
                }
                File newFile = newFile(destDir, entryName);
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        long totalBytes = 0;
                        while ((len = zip.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                            totalBytes += len;
                        }
                        fileCount++;
                        if (fileCount <= 20 || fileCount % 100 == 0) {
                            log.info("解压文件 {} 成功，大小：{} bytes", newFile.getAbsolutePath(), totalBytes);
                        }
                    }
                }
                zip.closeEntry();
            }
            log.info("解压完成（charset={}），共解压 {} 个文件，跳过 {} 个文件", charset.name(), fileCount, skippedCount);
        }
    }

    /**
     * 判断ZIP条目是否应该跳过（node_modules、target等）
     */
    private boolean shouldSkipEntry(String entryName) {
        if (entryName == null) {
            return true;
        }
        // 标准化路径分隔符
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (SKIP_DIRS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断异常是否由字符编码问题引起
     */
    private boolean isCharsetError(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.nio.charset.MalformedInputException
                    || t instanceof java.nio.charset.CharacterCodingException
                    || (t.getMessage() != null && t.getMessage().contains("malformed input"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
