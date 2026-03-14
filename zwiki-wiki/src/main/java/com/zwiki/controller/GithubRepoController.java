package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.service.auth.GithubAccessTokenService;
import com.zwiki.common.result.ResultVo;
import com.zwiki.domain.dto.GithubRepoDto;
import com.zwiki.service.github.GithubApiService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth/github")
public class GithubRepoController {

    private final GithubAccessTokenService accessTokenService;
    private final GithubApiService githubApiService;

    public GithubRepoController(GithubAccessTokenService accessTokenService, GithubApiService githubApiService) {
        this.accessTokenService = accessTokenService;
        this.githubApiService = githubApiService;
    }

    @GetMapping("/repos")
    public ResultVo<List<GithubRepoDto>> listPrivateRepos(@RequestParam(name = "q", required = false) String q) {
        // 使用AuthUtil统一获取userId
        String userId = AuthUtil.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            return ResultVo.error(401, "未登录");
        }

        String token = accessTokenService.getAccessTokenByUserId(userId);
        if (!StringUtils.hasText(token)) {
            return ResultVo.error(400, "GitHub授权无效或本地token不可用（可能未完成授权、token已失效，或服务端未配置zwiki.auth.token-crypto-key导致无法解密历史加密token）；请退出后重新使用GitHub登录授权");
        }

        try {
            List<GithubRepoDto> repos = githubApiService.listPrivateRepos(token);
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
                msg = "GitHub私有仓库获取失败";
            }
            return ResultVo.error(400, msg);
        } catch (Exception e) {
            return ResultVo.error(500, "GitHub私有仓库获取失败");
        }
    }
}
