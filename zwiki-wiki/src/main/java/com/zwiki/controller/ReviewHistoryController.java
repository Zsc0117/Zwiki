package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ReviewHistory;
import com.zwiki.repository.dao.ReviewHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @author pai
 * @description: 代码审查历史控制器
 * @date 2026/2/2
 */
@Slf4j
@RestController
@RequestMapping("/api/review/history")
@RequiredArgsConstructor
public class ReviewHistoryController {

    private final ReviewHistoryRepository reviewHistoryRepository;

    @GetMapping
    public ResultVo<Map<String, Object>> getHistory(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "repo", required = false) String repo) {

        Page<ReviewHistory> result;
        if (StringUtils.hasText(repo)) {
            result = reviewHistoryRepository.findByRepoFullNameOrderByCreatedAtDesc(repo, PageRequest.of(page, size));
        } else {
            result = reviewHistoryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("content", result.getContent());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("currentPage", result.getNumber());
        return ResultVo.success(data);
    }

    @GetMapping("/{id}")
    public ResultVo<ReviewHistory> getDetail(@PathVariable("id") Long id) {
        return reviewHistoryRepository.findById(id)
                .map(ResultVo::success)
                .orElse(ResultVo.error("Review history not found"));
    }
}
