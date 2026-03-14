package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileReaderTool {

    private final TaskRepository taskRepository;

    @Value("${zwiki.mcp-server.max-file-size:1048576}")
    private long maxFileSize;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "class", "jar", "war", "zip", "gz", "tar", "rar", "7z",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
            "mp3", "mp4", "avi", "mov", "wav", "flac",
            "exe", "dll", "so", "dylib", "o", "a",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "woff", "woff2", "ttf", "eot", "otf"
    );

    public String read_file(String task_id, String file_path) {

        log.info("MCP tool read_file called, taskId={}, filePath={}", task_id, file_path);

        if (!StringUtils.hasText(task_id)) {
            return "错误：task_id 不能为空。";
        }
        if (!StringUtils.hasText(file_path)) {
            return "错误：file_path 不能为空。";
        }

        Task task = taskRepository.findFirstByTaskId(task_id.trim()).orElse(null);
        if (task == null) {
            return "错误：未找到 taskId 为 \"" + task_id + "\" 的项目。";
        }

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) {
            return "错误：该项目的本地路径不存在。";
        }

        // Sanitize path
        String sanitized = file_path.trim().replace("\\", "/");
        if (sanitized.contains("..") || sanitized.startsWith("/")) {
            return "错误：非法的文件路径。不能包含 '..' 或以 '/' 开头。";
        }

        Path filePath = Paths.get(projectPath, sanitized).normalize();
        Path rootPath = Paths.get(projectPath).normalize();

        if (!filePath.startsWith(rootPath)) {
            return "错误：文件路径超出项目目录范围。";
        }

        if (!Files.exists(filePath)) {
            return "错误：文件不存在：" + sanitized + "\n提示：可以使用 get_repo_structure 工具查看项目文件结构。";
        }
        if (!Files.isRegularFile(filePath)) {
            return "错误：路径不是文件：" + sanitized;
        }

        try {
            long size = Files.size(filePath);
            if (size > maxFileSize) {
                return "错误：文件过大（" + (size / 1024) + "KB），超过最大限制（" + (maxFileSize / 1024) + "KB）。";
            }

            String ext = getExtension(filePath.getFileName().toString());
            if (BINARY_EXTENSIONS.contains(ext)) {
                return "错误：二进制文件无法读取：" + sanitized + "（类型: " + ext + "）";
            }

            String content = Files.readString(filePath);

            StringBuilder sb = new StringBuilder();
            sb.append("文件: `").append(sanitized).append("`\n");
            sb.append("项目: ").append(safe(task.getProjectName())).append("\n");
            sb.append("大小: ").append(size).append(" bytes\n\n");
            sb.append("```").append(ext).append("\n");
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("```\n");

            return sb.toString();

        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败：" + e.getMessage();
        }
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(未命名)";
    }
}
