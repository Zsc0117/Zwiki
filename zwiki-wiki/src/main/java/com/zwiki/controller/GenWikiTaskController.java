package com.zwiki.controller;
import com.zwiki.domain.param.CreateTaskParams;
import com.zwiki.domain.param.ListPageParams;
import com.zwiki.common.result.PageResult;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.service.CatalogueService;
import com.zwiki.service.TaskService;
import com.zwiki.domain.vo.CatalogueListVo;
import com.zwiki.domain.vo.TaskVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author pai
 * @description: wiki相关接口
 * @date 2026/1/20 18:47
 */

@Slf4j
@RestController
@RequestMapping("/api/task")
public class GenWikiTaskController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private CatalogueService catalogueService;

    @PostMapping("/create/git")
    public ResultVo<TaskVo> createFromGit(@RequestBody CreateTaskParams params) {
        return ResultVo.success(taskService.createFromGit(params));
    }

    @PostMapping("/create/repo")
    public ResultVo<TaskVo> createFromRepoUrl(@RequestBody CreateTaskParams params) {
        return ResultVo.success(taskService.createFromRepoUrl(params.getProjectUrl(), params.getBranch()));
    }

    @PostMapping("/create/zip")
    public ResultVo<TaskVo> createFromZip(
            @RequestPart("file") MultipartFile file,
            @RequestParam("projectName") String projectName,
            @RequestParam("userName") String userName,
            @RequestParam(value = "creatorUserId", required = false) String creatorUserId) {
        log.info("接收到ZIP文件上传请求，文件名：{}，大小：{}bytes，项目名：{}，用户名：{}",
                file.getOriginalFilename(),file.getSize(),projectName,userName);
        CreateTaskParams params = new CreateTaskParams();
        params.setProjectName(projectName);
        params.setUserName(userName);
        params.setCreatorUserId(creatorUserId);
        params.setSourceType("zip");
        try{
            return ResultVo.success(taskService.createFromZip(params,file));
        }catch (RuntimeException e){
            log.error("从ZIP文件创建任务失败：{}",e.getMessage());
            return ResultVo.error(e.getMessage());
        }
        
    }

    @PostMapping("/listPage")
    public ResultVo<PageResult<Task>> getTasksByPage(@RequestBody ListPageParams params) {
        PageResult<Task> page = taskService.getPageList(params);
        return ResultVo.success(page);
    }

    @GetMapping("/detail")
    public ResultVo<Task> getTaskByTaskId(@RequestParam("taskId") String taskId) {
        return ResultVo.success(taskService.getTaskByTaskId(taskId));
    }

    @PutMapping("/update")
    public ResultVo<Task> updateTask(@RequestBody TaskVo task) {
        return ResultVo.success(taskService.updateTaskByTaskId(task));
    }

    @DeleteMapping("/delete")
    public ResultVo<Void> deleteTask(@RequestParam("taskId") String taskId) {
        taskService.deleteTaskByTaskId(taskId);
        return ResultVo.success();
    }

    @PostMapping("/reanalyze")
    public ResultVo<TaskVo> reanalyze(@RequestParam("taskId") String taskId) {
        try {
            return ResultVo.success(taskService.reanalyze(taskId));
        } catch (RuntimeException e) {
            log.error("重新分析任务失败：{}", e.getMessage());
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/catalogue/detail")
    public ResultVo<List<Catalogue>> getCatalogueDetail(@RequestParam("taskId") String taskId) {
        return ResultVo.success(catalogueService.getCatalogueByTaskId(taskId));
    }

    @GetMapping("/catalogue/tree")
    public ResultVo<List<CatalogueListVo>> getCatalogueTree(@RequestParam("taskId") String taskId) {
        return ResultVo.success(catalogueService.getCatalogueTreeByTaskId(taskId));
    }

    @GetMapping("/catalogue/export")
    public ResponseEntity<byte[]> exportCatalogueAsMarkdown(@RequestParam("taskId") String taskId) {
        List<CatalogueListVo> tree = catalogueService.getCatalogueTreeByTaskId(taskId);
        if (tree == null || tree.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Task task = taskService.getTaskByTaskId(taskId);
        String projectName = task != null && StringUtils.hasText(task.getProjectName())
                ? task.getProjectName() : "wiki";

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(projectName).append(" - Wiki 文档\n\n");
        for (CatalogueListVo node : tree) {
            appendNodeMarkdown(sb, node, 1);
        }

        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = URLEncoder.encode(projectName + "-wiki.md", StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.length)
                .body(content);
    }

    private void appendNodeMarkdown(StringBuilder sb, CatalogueListVo node, int depth) {
        String heading = "#".repeat(Math.min(depth + 1, 6));
        String title = StringUtils.hasText(node.getName()) ? node.getName()
                : StringUtils.hasText(node.getTitle()) ? node.getTitle() : "";
        if (StringUtils.hasText(title)) {
            sb.append(heading).append(" ").append(title).append("\n\n");
        }
        if (StringUtils.hasText(node.getContent())) {
            sb.append(node.getContent()).append("\n\n");
        }
        if (node.getChildren() != null) {
            for (CatalogueListVo child : node.getChildren()) {
                appendNodeMarkdown(sb, child, depth + 1);
            }
        }
    }
}
