package com.zwiki.mcpserver.tool;

import com.zwiki.mcpserver.entity.Task;
import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepoStructureTool {

    private final TaskRepository taskRepository;

    @Value("${zwiki.mcp-server.max-tree-depth:10}")
    private int maxTreeDepth;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".settings", ".gradle",
            "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".next", ".nuxt", "vendor"
    );

    public String get_repo_structure(String task_id, String sub_path) {

        log.info("MCP tool get_repo_structure called, taskId={}, subPath={}", task_id, sub_path);

        if (!StringUtils.hasText(task_id)) {
            return "错误：task_id 不能为空。请先使用 list_projects 工具获取项目的 taskId。";
        }

        Task task = taskRepository.findFirstByTaskId(task_id.trim()).orElse(null);
        if (task == null) {
            return "错误：未找到 taskId 为 \"" + task_id + "\" 的项目。";
        }

        String projectPath = task.getProjectPath();
        if (!StringUtils.hasText(projectPath)) {
            return "错误：该项目的本地路径不存在。";
        }

        File root = new File(projectPath);
        if (!root.exists() || !root.isDirectory()) {
            return "错误：项目目录不存在或不可访问：" + projectPath;
        }

        // 处理子目录
        File targetDir = root;
        if (StringUtils.hasText(sub_path)) {
            String sanitized = sub_path.trim().replace("\\", "/");
            if (sanitized.contains("..")) {
                return "错误：路径不能包含 '..'";
            }
            Path resolved = Paths.get(projectPath, sanitized).normalize();
            if (!resolved.startsWith(Paths.get(projectPath).normalize())) {
                return "错误：路径超出项目目录范围。";
            }
            targetDir = resolved.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：子目录不存在：" + sanitized;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("项目: ").append(safe(task.getProjectName())).append("\n");
        sb.append("路径: ").append(targetDir.equals(root) ? "/" : sub_path).append("\n\n");
        sb.append("```\n");
        buildTree(sb, targetDir, "", 0);
        sb.append("```\n");

        return sb.toString();
    }

    private void buildTree(StringBuilder sb, File dir, String prefix, int depth) {
        if (depth > maxTreeDepth) {
            sb.append(prefix).append("... (max depth reached)\n");
            return;
        }

        File[] children = dir.listFiles();
        if (children == null || children.length == 0) return;

        // Sort: directories first, then files
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            String name = child.getName();

            if (child.isDirectory() && SKIP_DIRS.contains(name)) continue;
            if (name.startsWith(".") && child.isFile()) continue;

            boolean isLast = (i == children.length - 1) ||
                    isLastVisible(children, i);
            String connector = isLast ? "└── " : "├── ";
            String childPrefix = isLast ? "    " : "│   ";

            if (child.isDirectory()) {
                sb.append(prefix).append(connector).append(name).append("/\n");
                buildTree(sb, child, prefix + childPrefix, depth + 1);
            } else {
                sb.append(prefix).append(connector).append(name).append("\n");
            }
        }
    }

    private boolean isLastVisible(File[] children, int currentIndex) {
        for (int i = currentIndex + 1; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory() && SKIP_DIRS.contains(child.getName())) continue;
            if (child.getName().startsWith(".") && child.isFile()) continue;
            return false;
        }
        return true;
    }

    private String safe(String s) {
        return StringUtils.hasText(s) ? s : "(未命名)";
    }
}
