package com.zwiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwiki.util.AuthUtil;
import com.zwiki.service.auth.GithubAccessTokenService;
import com.zwiki.service.auth.SaTokenUserContext;
import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.common.result.PageResult;
import com.zwiki.domain.dto.GenCatalogueDTO;
import com.zwiki.domain.enums.TaskStatusEnum;
import com.zwiki.domain.param.CreateTaskParams;
import com.zwiki.domain.param.ListPageParams;
import com.zwiki.domain.vo.TaskVo;
import com.zwiki.repository.context.ExecutionContext;
import com.zwiki.repository.dao.TaskRepository;
import com.zwiki.repository.entity.Catalogue;
import com.zwiki.repository.entity.Task;
import com.zwiki.service.notification.NotificationService;
import com.zwiki.util.RedisUtil;
import com.zwiki.util.TaskIdGenerator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author pai
 * @description: 任务服务实现类
 * @date 2026/1/23 16:05
 */
@Slf4j
@Service
public class TaskService {
    @Resource(name = "CreateTaskExecutor")
    private ThreadPoolTaskExecutor createTaskExecutor;

    @Autowired
    private GitService gitService;

    @Autowired
    private FileService fileService;

    @Autowired
    private CatalogueService catalogueService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private GithubAccessTokenService githubAccessTokenService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TaskProgressService taskProgressService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskQueueService taskQueueService;

    private String taskDetailCacheKey(String taskId) {
        return RedisKeyEnum.TASK_DETAIL_CACHE.getKey(taskId);
    }

    private void cacheTaskDetail(Task task) {
        if (task == null || !StringUtils.hasText(task.getTaskId())) {
            return;
        }
        try {
            redisUtil.set(
                    taskDetailCacheKey(task.getTaskId()),
                    objectMapper.writeValueAsString(task),
                    RedisKeyEnum.TASK_DETAIL_CACHE.getExpireTime()
            );
        } catch (Exception ignore) {
        }
    }

    private void evictTaskDetailCache(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        try {
            redisUtil.delete(taskDetailCacheKey(taskId));
        } catch (Exception ignore) {
        }
    }

    private void pushProgress(Task task, int progress, String step) {
        if (task == null) {
            return;
        }
        String userId = task.getUserId();
        if (!StringUtils.hasText(userId)) {
            return;
        }
        taskProgressService.update(task.getTaskId(), progress, step);
        notificationService.notifyTaskProgress(userId, task.getTaskId(), progress, step);
    }

    private boolean isGitSource(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        return "git".equalsIgnoreCase(sourceType) || "github".equalsIgnoreCase(sourceType);
    }

    private String getCurrentUserId() {
        // 使用AuthUtil统一获取userId
        return AuthUtil.getCurrentUserId();
    }

    private String getCurrentGithubLogin() {
        return AuthUtil.getCurrentGithubLogin();
    }

    private String normalizeSourceType(String sourceType, MultipartFile file) {
        if (sourceType == null || sourceType.trim().isEmpty()) {
            return (file != null) ? "zip" : "github";
        }
        if (isGitSource(sourceType)) {
            return "github";
        }
        if ("zip".equalsIgnoreCase(sourceType)) {
            return "zip";
        }
        return (file != null) ? "zip" : "github";
    }

    public Task createTask(CreateTaskParams params, MultipartFile file) {
        if (params == null) {
            throw new RuntimeException("创建任务参数不能为空");
        }

        String currentUserId = getCurrentUserId();
        if (StringUtils.hasText(currentUserId)) {
            params.setCreatorUserId(currentUserId);
        }

        String githubLogin = getCurrentGithubLogin();
        if (StringUtils.hasText(githubLogin)) {
            params.setUserName(githubLogin);
        }

        String normalizedSourceType = normalizeSourceType(params.getSourceType(), file);
        params.setSourceType(normalizedSourceType);

        //根据项目来源处理本地目录
        final String finalLocalPath;
        final java.io.File tempZipFile;
        if (isGitSource(normalizedSourceType)) {
            String ownerKey = params.getUserName();
            if (!StringUtils.hasText(ownerKey)) {
                throw new RuntimeException("创建任务缺少GitHub用户名(userName)");
            }
            finalLocalPath = fileService.getRepositoryPath(ownerKey, params.getProjectName());
            tempZipFile = null;
        } else {
            // ZIP模式：先保存到临时文件，解压放到异步线程中执行
            finalLocalPath = fileService.getRepositoryPath(params.getUserName(), params.getProjectName());
            try {
                tempZipFile = java.io.File.createTempFile("zwiki-upload-", ".zip");
                file.transferTo(tempZipFile);
                log.info("ZIP文件已保存到临时文件：{}，大小：{} bytes", tempZipFile.getAbsolutePath(), tempZipFile.length());
            } catch (Exception e) {
                log.error("保存ZIP临时文件失败", e);
                throw new RuntimeException("保存上传文件失败: " + e.getMessage(), e);
            }
        }

        Task task = insertTask(params, finalLocalPath, file);

        // 入队并获取排队快照
        try {
            taskQueueService.enqueueTask(task.getTaskId());
            if (StringUtils.hasText(task.getUserId())) {
                TaskQueueService.QueueSnapshot snapshot =
                        taskQueueService.getQueueSnapshot(task.getTaskId());
                notificationService.notifyTaskQueued(
                        task.getUserId(), task.getTaskId(), task.getProjectName(),
                        snapshot.getPosition(), snapshot.getAheadCount(), snapshot.getEstimatedWaitMinutes());
            }
        } catch (Exception e) {
            log.warn("入队通知失败: taskId={}", task.getTaskId(), e);
        }

        // 标记任务开始处理
        try {
            taskQueueService.markTaskStarted(task.getTaskId());
            if (StringUtils.hasText(task.getUserId())) {
                notificationService.notifyTaskStarted(task.getUserId(), task.getTaskId(), task.getProjectName());
            }
        } catch (Exception ignore) {
        }

        pushProgress(task, 1, "任务已创建");

        ExecutionContext context = new ExecutionContext();
        context.setTask(task);
        context.setCreateParams(params);
        context.setTaskId(task.getTaskId());
        context.setLocalPath(finalLocalPath);

        final String asyncUserId = task.getUserId();

        //异步处理任务
        createTaskExecutor.execute(() -> {
            if (StringUtils.hasText(asyncUserId)) {
                SaTokenUserContext.setUserId(asyncUserId);
            }
            try {
                if (isGitSource(normalizedSourceType)) {
                    pushProgress(task, 5, "开始拉取仓库");
                    log.info("开始从Git仓库拉取项目");

                    boolean hasCred = StringUtils.hasText(params.getGitPassword()) || StringUtils.hasText(params.getPassword());
                    if (!hasCred && StringUtils.hasText(params.getCreatorUserId())) {
                        String token = githubAccessTokenService.getAccessTokenByUserId(params.getCreatorUserId());
                        if (StringUtils.hasText(token)) {
                            params.setGitUserName("x-access-token");
                            params.setGitPassword(token);
                        }
                    }

                    String clonedPath = gitService.cloneRepository(params, finalLocalPath);
                    if (clonedPath != null && !clonedPath.isBlank()) {
                        context.setLocalPath(clonedPath);
                        task.setProjectPath(clonedPath);
                        task.setSourceType("github");
                        task.setUpdateTime(LocalDateTime.now());
                        taskRepository.save(task);
                    }
                    log.info("拉取项目成功");
                    pushProgress(task, 10, "仓库拉取完成");
                } else {
                    // 异步解压ZIP文件
                    pushProgress(task, 3, "开始解压ZIP文件");
                    log.info("开始异步解压ZIP文件");
                    try {
                        String unzippedPath = fileService.unzipToProjectDir(tempZipFile, params.getUserName(), params.getProjectName());
                        context.setLocalPath(unzippedPath);
                        task.setProjectPath(unzippedPath);
                        task.setSourceType("zip");
                        task.setUpdateTime(LocalDateTime.now());
                        taskRepository.save(task);
                        log.info("ZIP文件解压完成");
                        pushProgress(task, 10, "代码解压完成");
                    } finally {
                        if (tempZipFile != null && tempZipFile.exists()) {
                            tempZipFile.delete();
                            log.info("临时ZIP文件已删除");
                        }
                    }
                }
                executeTask(context);
            } catch (Exception e) {
                log.error("任务{}执行失败：{}", task.getTaskId(), e.getMessage());
                task.setStatus(TaskStatusEnum.FAILED);
                task.setFailReason(e.getMessage());
                task.setUpdateTime(LocalDateTime.now());
                taskRepository.save(task);

                // 确保临时文件被清理
                if (tempZipFile != null && tempZipFile.exists()) {
                    tempZipFile.delete();
                }

                try {
                    taskQueueService.markTaskFinished(task.getTaskId());
                    taskProgressService.fail(task.getTaskId(), "分析失败: " + e.getMessage());
                    if (StringUtils.hasText(task.getUserId())) {
                        notificationService.notifyTaskFailed(task.getUserId(), task.getTaskId(), task.getProjectName(), e.getMessage());
                    }
                } catch (Exception ignore) {
                }
            } finally {
                SaTokenUserContext.clear();
            }
        });
        return task;
    }

    private void executeTask(ExecutionContext context) {
        Task task = context.getTask();
        try {
            //生成项目目录
            pushProgress(task, 15, "扫描项目文件");
            String fileTree = fileService.getFileTree(context.getLocalPath());

            pushProgress(task, 25, "生成目录结构");
            GenCatalogueDTO<Catalogue> catalogueDTO = catalogueService.generateCatalogue(fileTree, context);

            // 缓存项目路径到CatalogueService，避免循环依赖
            catalogueService.cacheTaskProjectPath(context.getTaskId(), context.getLocalPath());

            //生成目录详情 - 传递projectName
            String projectName = task.getProjectName();

            int totalDocs = (catalogueDTO != null && catalogueDTO.getCatalogueList() != null)
                    ? catalogueDTO.getCatalogueList().size()
                    : 0;
            taskProgressService.initDocGeneration(task.getTaskId(), totalDocs, "开始生成文档");
            if (StringUtils.hasText(task.getUserId())) {
                notificationService.notifyTaskProgress(task.getUserId(), task.getTaskId(), 50, "开始生成文档");
            }

            catalogueService.parallelGenerateCatalogueDetail(fileTree, catalogueDTO, context.getLocalPath(), projectName);
            task.setUpdateTime(LocalDateTime.now());
        } catch (Exception e) {
            log.error("任务执行失败", e);
            task.setStatus(TaskStatusEnum.FAILED);
            task.setFailReason(e.getMessage());
            task.setUpdateTime(LocalDateTime.now());

            try {
                taskProgressService.fail(task.getTaskId(), "分析失败: " + e.getMessage());
                if (StringUtils.hasText(task.getUserId())) {
                    notificationService.notifyTaskFailed(task.getUserId(), task.getTaskId(), task.getProjectName(), e.getMessage());
                }
            } catch (Exception ignore) {
            }
        } finally {
            taskRepository.save(task);
            // 清理CatalogueService中的缓存数据
            catalogueService.cleanupTaskCache(context.getTaskId());
        }
    }

    private Task insertTask(CreateTaskParams params, String localPath, MultipartFile file) {
        String sourceType = normalizeSourceType(params.getSourceType(), file);
        Task task = Task.builder()
                .taskId(TaskIdGenerator.generate())
                .userId(params.getCreatorUserId())
                .projectName(params.getProjectName())
                .projectUrl(params.getProjectUrl())
                .userName(params.getUserName())
                .sourceType(sourceType)
                .projectPath(localPath)
                .status(TaskStatusEnum.IN_PROGRESS)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        return taskRepository.save(task);
    }

    public PageResult<Task> getPageList(ListPageParams params) {
        Pageable pageable = PageRequest.of(
                Math.max(0, params.getPageIndex() - 1),
                params.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id")
        );

        Page<Task> page;
        if (params.getTaskId() != null && !params.getTaskId().isEmpty()) {
            // 简化实现：按taskId精确查找时，分页意义不大，直接返回单条结果封装
            Task task = taskRepository.findFirstByTaskId(params.getTaskId()).orElse(null);
            if (task == null) {
                return PageResult.of(java.util.List.of(), 0, params.getPageSize(), params.getPageIndex());
            }
            return PageResult.of(java.util.List.of(task), 1, params.getPageSize(), params.getPageIndex());
        }

        if (params.getUserId() != null && !params.getUserId().isEmpty()) {
            page = taskRepository.findAll((root, query, cb) -> cb.equal(root.get("userId"), params.getUserId()), pageable);
        } else if (params.getProjectName() != null && !params.getProjectName().isEmpty()) {
            page = taskRepository.findAll((root, query, cb) -> cb.equal(root.get("projectName"), params.getProjectName()), pageable);
        } else if (params.getUserName() != null && !params.getUserName().isEmpty()) {
            page = taskRepository.findAll((root, query, cb) -> cb.equal(root.get("userName"), params.getUserName()), pageable);
        } else {
            page = taskRepository.findAll(pageable);
        }

        return PageResult.of(page.getContent(), page.getTotalElements(), params.getPageSize(), params.getPageIndex());
    }

    public Task getTaskByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }

        try {
            String cached = redisUtil.get(taskDetailCacheKey(taskId));
            if (StringUtils.hasText(cached)) {
                return objectMapper.readValue(cached, Task.class);
            }
        } catch (Exception ignore) {
        }

        Task task = taskRepository.findFirstByTaskId(taskId).orElse(null);
        cacheTaskDetail(task);
        return task;
    }

    public Task updateTaskByTaskId(TaskVo taskVo) {
        Task task = getTaskByTaskId(taskVo.getTaskId());
        task.setProjectName(taskVo.getProjectName());
        task.setProjectUrl(taskVo.getProjectUrl());
        task.setUserName(taskVo.getUserName());
        task.setUpdateTime(LocalDateTime.now());
        Task saved = taskRepository.save(task);
        cacheTaskDetail(saved);
        return saved;
    }

    public void deleteTaskByTaskId(String taskId) {
        Task task = getTaskByTaskId(taskId);
        if (task != null) {
            //获取任务关联的项目信息
            String projectName = task.getProjectName();
            String userName = task.getUserName();
            try {
                //删除项目目录
                fileService.deleteProjectDirectory(userName, projectName);
                log.info("任务{}的项目目录 {} 删除成功", taskId, projectName);
            } catch (Exception e) {
                log.error("任务{}的项目目录 {} 删除失败: {}", taskId, projectName, e.getMessage());
            }

            //删除目录
            catalogueService.deleteCatalogueByTaskId(taskId);

            taskRepository.deleteByTaskId(taskId);
            evictTaskDetailCache(taskId);
            log.info("任务{}删除成功", taskId);
        } else {
            log.info("任务{}不存在", taskId);
        }

    }

    public TaskVo createFromGit(CreateTaskParams params) {
        params.setSourceType("github");
        Task task = createTask(params, null);
        return toTaskVo(task);
    }

    private String normalizeProjectUrl(String projectUrl) {
        if (!StringUtils.hasText(projectUrl)) {
            return projectUrl;
        }
        String url = projectUrl.trim();
        if (url.startsWith("git@")) {
            String rest = url.substring("git@".length());
            int colonIndex = rest.indexOf(':');
            if (colonIndex > 0 && colonIndex < rest.length() - 1) {
                String host = rest.substring(0, colonIndex);
                String path = rest.substring(colonIndex + 1);
                if (path.endsWith(".git")) {
                    path = path.substring(0, path.length() - 4);
                }
                return "https://" + host + "/" + path;
            }
        }
        return url;
    }

    private String parseProjectNameFromUrl(String projectUrl) {
        if (!StringUtils.hasText(projectUrl)) {
            return null;
        }
        String url = projectUrl.trim();
        int qIndex = url.indexOf('?');
        if (qIndex >= 0) {
            url = url.substring(0, qIndex);
        }
        int hashIndex = url.indexOf('#');
        if (hashIndex >= 0) {
            url = url.substring(0, hashIndex);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String[] parts = url.split("/");
        if (parts.length == 0) {
            return null;
        }
        String last = parts[parts.length - 1];
        if (last.endsWith(".git")) {
            last = last.substring(0, last.length() - 4);
        }
        return StringUtils.hasText(last) ? last : null;
    }

    public TaskVo createFromRepoUrl(String projectUrl, String branch) {
        if (!StringUtils.hasText(projectUrl)) {
            throw new RuntimeException("项目仓库URL不能为空");
        }

        String normalizedUrl = normalizeProjectUrl(projectUrl);
        String projectName = parseProjectNameFromUrl(normalizedUrl);
        if (!StringUtils.hasText(projectName)) {
            throw new RuntimeException("无法从仓库链接解析项目名称");
        }

        Optional<Task> existing = taskRepository.findFirstByProjectUrlOrderByIdDesc(normalizedUrl);
        if (existing.isEmpty() && !normalizedUrl.equals(projectUrl.trim())) {
            existing = taskRepository.findFirstByProjectUrlOrderByIdDesc(projectUrl.trim());
        }
        if (existing.isPresent()) {
            return toTaskVo(existing.get());
        }

        CreateTaskParams params = new CreateTaskParams();
        params.setSourceType("github");
        params.setProjectUrl(normalizedUrl);
        params.setBranch(branch);
        params.setProjectName(projectName);

        Task task = createTask(params, null);
        return toTaskVo(task);
    }

    public TaskVo createFromZip(CreateTaskParams params, MultipartFile file) {
        try {
            Task task = createTask(params, file);
            return toTaskVo(task);
        } catch (Exception e) {
            log.error("处理ZIP文件失败", e);
            throw new RuntimeException("处理ZIP文件失败:" + e.getMessage());
        }
    }

    public TaskVo reanalyze(String taskId) {
        Task task = getTaskByTaskId(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在: " + taskId);
        }

        // Delete old catalogues
        catalogueService.deleteCatalogueByTaskId(taskId);

        // Reset task status
        task.setStatus(TaskStatusEnum.IN_PROGRESS);
        task.setFailReason(null);
        task.setUpdateTime(LocalDateTime.now());
        taskRepository.save(task);

        pushProgress(task, 1, "重新分析已启动");

        // 入队并获取排队快照
        try {
            taskQueueService.enqueueTask(task.getTaskId());
            if (StringUtils.hasText(task.getUserId())) {
                TaskQueueService.QueueSnapshot snapshot =
                        taskQueueService.getQueueSnapshot(task.getTaskId());
                notificationService.notifyTaskQueued(
                        task.getUserId(), task.getTaskId(), task.getProjectName(),
                        snapshot.getPosition(), snapshot.getAheadCount(), snapshot.getEstimatedWaitMinutes());
            }
        } catch (Exception e) {
            log.warn("重分析入队通知失败: taskId={}", task.getTaskId(), e);
        }

        try {
            taskQueueService.markTaskStarted(task.getTaskId());
            if (StringUtils.hasText(task.getUserId())) {
                notificationService.notifyTaskStarted(task.getUserId(), task.getTaskId(), task.getProjectName());
            }
        } catch (Exception ignore) {
        }

        String localPath = task.getProjectPath();

        ExecutionContext context = new ExecutionContext();
        context.setTask(task);
        context.setTaskId(task.getTaskId());
        context.setLocalPath(localPath);

        CreateTaskParams params = new CreateTaskParams();
        params.setProjectName(task.getProjectName());
        params.setProjectUrl(task.getProjectUrl());
        params.setUserName(task.getUserName());
        params.setSourceType(task.getSourceType());
        params.setCreatorUserId(task.getUserId());
        context.setCreateParams(params);

        final String asyncUserId = task.getUserId();

        // Re-run analysis asynchronously
        createTaskExecutor.execute(() -> {
            if (StringUtils.hasText(asyncUserId)) {
                SaTokenUserContext.setUserId(asyncUserId);
            }
            try {
                if (isGitSource(task.getSourceType())) {
                    // Resolve credentials once
                    String gitUser = null;
                    String gitPass = null;
                    if (StringUtils.hasText(params.getCreatorUserId())) {
                        String token = githubAccessTokenService.getAccessTokenByUserId(params.getCreatorUserId());
                        if (StringUtils.hasText(token)) {
                            gitUser = "x-access-token";
                            gitPass = token;
                        }
                    }

                    if (localPath != null && new java.io.File(localPath).exists()) {
                        // Local repo exists — git pull to get latest code
                        pushProgress(task, 5, "拉取最新代码");
                        try {
                            gitService.pullRepository(localPath, gitUser, gitPass);
                            pushProgress(task, 10, "代码更新完成");
                        } catch (Exception pullEx) {
                            // Pull failed (e.g. merge conflict) — fall back to fresh clone
                            log.warn("Git pull 失败，回退到重新克隆: taskId={}, error={}", task.getTaskId(), pullEx.getMessage());
                            pushProgress(task, 5, "拉取失败，重新克隆仓库");
                            params.setGitUserName(gitUser);
                            params.setGitPassword(gitPass);
                            String repoPath = fileService.getRepositoryPath(task.getUserName(), task.getProjectName());
                            String clonedPath = gitService.cloneRepository(params, repoPath);
                            if (clonedPath != null && !clonedPath.isBlank()) {
                                context.setLocalPath(clonedPath);
                                task.setProjectPath(clonedPath);
                                task.setUpdateTime(LocalDateTime.now());
                                taskRepository.save(task);
                            }
                            pushProgress(task, 10, "仓库重新克隆完成");
                        }
                    } else {
                        // Local repo missing — fresh clone
                        pushProgress(task, 5, "克隆仓库");
                        params.setGitUserName(gitUser);
                        params.setGitPassword(gitPass);
                        String repoPath = fileService.getRepositoryPath(task.getUserName(), task.getProjectName());
                        String clonedPath = gitService.cloneRepository(params, repoPath);
                        if (clonedPath != null && !clonedPath.isBlank()) {
                            context.setLocalPath(clonedPath);
                            task.setProjectPath(clonedPath);
                            task.setUpdateTime(LocalDateTime.now());
                            taskRepository.save(task);
                        }
                        pushProgress(task, 10, "仓库克隆完成");
                    }
                } else {
                    pushProgress(task, 10, "使用已有代码");
                }
                executeTask(context);
            } catch (Exception e) {
                log.error("重新分析任务{}失败：{}", task.getTaskId(), e.getMessage());
                task.setStatus(TaskStatusEnum.FAILED);
                task.setFailReason(e.getMessage());
                task.setUpdateTime(LocalDateTime.now());
                taskRepository.save(task);
                try {
                    taskQueueService.markTaskFinished(task.getTaskId());
                    taskProgressService.fail(task.getTaskId(), "重新分析失败: " + e.getMessage());
                    if (StringUtils.hasText(task.getUserId())) {
                        notificationService.notifyTaskFailed(task.getUserId(), task.getTaskId(), task.getProjectName(), e.getMessage());
                    }
                } catch (Exception ignore) {
                }
            } finally {
                SaTokenUserContext.clear();
            }
        });

        return toTaskVo(task);
    }

    private TaskVo toTaskVo(Task task) {
        if (task == null) {
            return null;
        }
        TaskVo vo = new TaskVo();
        vo.setId(task.getId());
        vo.setTaskId(task.getTaskId());
        vo.setUserId(task.getUserId());
        vo.setProjectName(task.getProjectName());
        vo.setProjectUrl(task.getProjectUrl());
        vo.setUserName(task.getUserName());
        vo.setSourceType(task.getSourceType());
        vo.setProjectPath(task.getProjectPath());
        vo.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
        vo.setFailReason(task.getFailReason());
        vo.setCreateTime(task.getCreateTime() != null ? task.getCreateTime().toString() : null);
        vo.setUpdateTime(task.getUpdateTime() != null ? task.getUpdateTime().toString() : null);
        return vo;
    }


}
