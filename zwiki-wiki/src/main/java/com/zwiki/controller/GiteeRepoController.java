package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.service.auth.GiteeAccessTokenService;
import com.zwiki.common.result.ResultVo;
import com.zwiki.domain.dto.GithubRepoDto;
import com.zwiki.service.github.GiteeApiService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gitee仓库控制器
 */
@RestController
@RequestMapping("/api/auth/gitee")
public class GiteeRepoController {

    private final GiteeAccessTokenService accessTokenService;
    private final GiteeApiService giteeApiService;

    public GiteeRepoController(GiteeAccessTokenService accessTokenService, GiteeApiService giteeApiService) {
        this.accessTokenService = accessTokenService;
        this.giteeApiService = giteeApiService;
    }

    /**
     * 获取用户的所有仓库（包括私有仓库）
     */
    @GetMapping("/repos")
    public ResultVo<List<GithubRepoDto>> listRepos(@RequestParam(name = "q", required = false) String q) {
        String userId = AuthUtil.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            return ResultVo.error(401, "未登录");
        }

        String token = accessTokenService.getAccessTokenByUserId(userId);
        if (!StringUtils.hasText(token)) {
            return ResultVo.error(400, "Gitee授权无效或本地token不可用（可能未完成授权、token已失效，或服务端未配置zwiki.auth.token-crypto-key导致无法解密历史加密token）；请退出后重新使用Gitee登录授权");
        }

        try {
            List<GithubRepoDto> repos = giteeApiService.listUserRepos(token);
            if (StringUtils.hasText(q)) {
                String query = q.trim().toLowerCase();
                repos = repos.stream()
                        .filter(r -> (r.getFullName() != null && r.getFullName().toLowerCase().contains(query))
                                || (r.getName() != null && r.getName().toLowerCase().contains(query)))
                        .toList();
            }
            return ResultVo.success(repos);
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (!StringUtils.hasText(msg)) {
                msg = "Gitee仓库获取失败";
            }
            return ResultVo.error(400, msg);
        } catch (Exception e) {
            return ResultVo.error(500, "Gitee仓库获取失败");
        }
    }

    /**
     * 获取用户的私有仓库
     */
    @GetMapping("/repos/private")
    public ResultVo<List<GithubRepoDto>> listPrivateRepos(@RequestParam(name = "q", required = false) String q) {
        String userId = AuthUtil.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            return ResultVo.error(401, "未登录");
        }

        String token = accessTokenService.getAccessTokenByUserId(userId);
        if (!StringUtils.hasText(token)) {
            return ResultVo.error(400, "Gitee授权无效或本地token不可用；请退出后重新使用Gitee登录授权");
        }

        try {
            List<GithubRepoDto> repos = giteeApiService.listPrivateRepos(token);
            if (StringUtils.hasText(q)) {
                String query = q.trim().toLowerCase();
                repos = repos.stream()
                        .filter(r -> (r.getFullName() != null && r.getFullName().toLowerCase().contains(query))
                                || (r.getName() != null && r.getName().toLowerCase().contains(query)))
                        .toList();
            }
            return ResultVo.success(repos);
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (!StringUtils.hasText(msg)) {
                msg = "Gitee私有仓库获取失败";
            }
            return ResultVo.error(400, msg);
        } catch (Exception e) {
            return ResultVo.error(500, "Gitee私有仓库获取失败");
        }
    }
}
