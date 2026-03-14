package com.zwiki.mcpserver.controller;

import com.zwiki.mcpserver.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final TaskRepository taskRepository;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "zwiki-mcp-server");
        result.put("version", "1.0.0");
        try {
            long projectCount = taskRepository.count();
            result.put("projectCount", projectCount);
        } catch (Exception e) {
            log.warn("无法查询项目数量: {}", e.getMessage());
            result.put("projectCount", "N/A");
        }
        return result;
    }
}
