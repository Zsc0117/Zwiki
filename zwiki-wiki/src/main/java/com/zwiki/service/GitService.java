package com.zwiki.service;

import com.zwiki.common.exception.BusinessException;
import com.zwiki.domain.param.CreateTaskParams;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * @author pai
 * @description: git 服务实现类
 * @date 2026/1/20 19:05
 */
@Service
@Slf4j
public class GitService {

    @Value("${git.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${git.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${git.proxy.port:7890}")
    private int proxyPort;

    @Value("${git.clone.timeout-seconds:120}")
    private int cloneTimeoutSeconds;

    @Value("${git.clone.retry.max-attempts:3}")
    private int cloneRetryMaxAttempts;

    @Value("${git.clone.retry.backoff-millis:2000}")
    private long cloneRetryBackoffMillis;

    private Map<String, String> applyGitProxySystemProperties() {
        Map<String, String> previous = new HashMap<>();
        if (!(proxyEnabled && StringUtils.hasText(proxyHost) && proxyPort > 0)) {
            return previous;
        }

        String[] keys = new String[] {
                "http.proxyHost",
                "http.proxyPort",
                "https.proxyHost",
                "https.proxyPort"
        };
        for (String key : keys) {
            previous.put(key, System.getProperty(key));
        }

        System.setProperty("http.proxyHost", proxyHost);
        System.setProperty("http.proxyPort", String.valueOf(proxyPort));
        System.setProperty("https.proxyHost", proxyHost);
        System.setProperty("https.proxyPort", String.valueOf(proxyPort));
        return previous;
    }

    private void restoreSystemProperties(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    private void deleteDirectoryQuietly(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            return;
        }
        Path path = Path.of(localPath);
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException ignore) {
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String cloneRepository(CreateTaskParams createTaskParams, String localPath) {
        if (!StringUtils.hasText(createTaskParams.getProjectUrl())) {
            throw new BusinessException("项目仓库URL不能为空");
        }

        int attempts = Math.max(1, cloneRetryMaxAttempts);
        Exception lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                deleteDirectoryQuietly(localPath);
                sleepQuietly(cloneRetryBackoffMillis * (long) attempt);
            }

            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(createTaskParams.getProjectUrl())
                    .setDirectory(new File(localPath));

            if (cloneTimeoutSeconds > 0) {
                cloneCommand.setTimeout(cloneTimeoutSeconds);
            }
            if (StringUtils.hasText(createTaskParams.getBranch())) {
                cloneCommand.setBranch(createTaskParams.getBranch());
            }

            String authUser = StringUtils.hasText(createTaskParams.getGitUserName()) ? createTaskParams.getGitUserName() : createTaskParams.getUserName();
            String authPass = StringUtils.hasText(createTaskParams.getGitPassword()) ? createTaskParams.getGitPassword() : createTaskParams.getPassword();
            if (StringUtils.hasText(authPass)) {
                if (!StringUtils.hasText(authUser)) {
                    authUser = "x-access-token";
                }
                cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(authUser, authPass));
            }

            Map<String, String> previousProxyProperties = applyGitProxySystemProperties();
            if (proxyEnabled && StringUtils.hasText(proxyHost) && proxyPort > 0) {
                log.info("Git代理已启用: {}:{} (attempt {}/{})", proxyHost, proxyPort, attempt, attempts);
            }

            try {
                cloneCommand.call().close();
                return localPath;
            } catch (Exception e) {
                lastException = e;
                log.error("克隆仓库失败 attempt {}/{}: url={}, localPath={}, error={}",
                        attempt,
                        attempts,
                        createTaskParams.getProjectUrl(),
                        localPath,
                        e.getMessage(),
                        e);
            } finally {
                restoreSystemProperties(previousProxyProperties);
            }
        }

        if (lastException != null) {
            throw new BusinessException("克隆仓库失败：" + lastException.getMessage());
        }
        return localPath;
    }

    public void pullRepository(String localPath, String username, String password) {
        if (!StringUtils.hasText(localPath)) {
            throw new BusinessException("本地仓库路径不能为空");
        }
        File repoDir = new File(localPath);
        if (!repoDir.exists() || !repoDir.isDirectory()) {
            throw new BusinessException("本地仓库目录不存在: " + localPath);
        }

        int attempts = Math.max(1, cloneRetryMaxAttempts);
        Exception lastException = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                sleepQuietly(cloneRetryBackoffMillis * (long) attempt);
            }

            Map<String, String> previousProxyProperties = applyGitProxySystemProperties();
            try (Git git = Git.open(repoDir)) {
                var pullCommand = git.pull().setTimeout(cloneTimeoutSeconds);
                if (StringUtils.hasText(password)) {
                    String user = StringUtils.hasText(username) ? username : "x-access-token";
                    pullCommand.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(user, password));
                }
                pullCommand.call();
                log.info("Git pull 成功: path={}, attempt={}/{}", localPath, attempt, attempts);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("Git pull 失败 attempt {}/{}: path={}, error={}",
                        attempt, attempts, localPath, e.getMessage());
            } finally {
                restoreSystemProperties(previousProxyProperties);
            }
        }

        if (lastException != null) {
            throw new BusinessException("拉取最新代码失败：" + lastException.getMessage());
        }
    }
}
