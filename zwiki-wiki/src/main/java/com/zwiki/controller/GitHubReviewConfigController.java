package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.domain.dto.GitHubReviewConfigDto;
import com.zwiki.service.github.GitHubReviewConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author pai
 * @description: GitHub代码审查配置控制器
 * @date 2026/2/1
 */
@RestController
@RequestMapping("/api/auth/github/review-config")
@RequiredArgsConstructor
public class GitHubReviewConfigController {

    private final GitHubReviewConfigService configService;

    @GetMapping
    public ResultVo<List<GitHubReviewConfigDto>> list() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(configService.listByUser(userId));
    }

    @GetMapping("/{id}")
    public ResultVo<GitHubReviewConfigDto> getById(@PathVariable("id") Long id) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        GitHubReviewConfigDto dto = configService.getById(userId, id);
        if (dto == null) {
            return ResultVo.error(404, "配置不存在");
        }
        return ResultVo.success(dto);
    }

    @PostMapping
    public ResultVo<GitHubReviewConfigDto> create(@RequestBody GitHubReviewConfigDto dto) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        try {
            return ResultVo.success(configService.create(userId, dto));
        } catch (IllegalArgumentException e) {
            return ResultVo.error(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResultVo<GitHubReviewConfigDto> update(@PathVariable("id") Long id, @RequestBody GitHubReviewConfigDto dto) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        try {
            return ResultVo.success(configService.update(userId, id, dto));
        } catch (IllegalArgumentException e) {
            return ResultVo.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResultVo<Void> delete(@PathVariable("id") Long id) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        configService.delete(userId, id);
        return ResultVo.success();
    }

    @GetMapping("/token-status")
    public ResultVo<Boolean> tokenStatus() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        return ResultVo.success(configService.hasValidToken(userId));
    }
}
