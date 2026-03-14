package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.service.mcp.McpAdminService;
import com.zwiki.service.mcp.McpJsonConfigProvider;
import com.zwiki.service.mcp.McpToolCallbackProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpAdminService mcpAdminService;
    private final McpJsonConfigProvider configProvider;
    private final McpToolCallbackProvider toolCallbackProvider;

    public McpController(McpAdminService mcpAdminService,
                         McpJsonConfigProvider configProvider,
                         McpToolCallbackProvider toolCallbackProvider) {
        this.mcpAdminService = mcpAdminService;
        this.configProvider = configProvider;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @GetMapping("/config")
    public ResultVo<String> getConfig() {
        return ResultVo.success(configProvider.readRawJson());
    }

    @PutMapping("/config")
    public ResultVo<Void> saveConfig(@RequestBody String json) {
        try {
            configProvider.writeRawJson(json);
            toolCallbackProvider.reload();
            return ResultVo.success();
        }
        catch (Exception e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/servers")
    public ResultVo<List<McpAdminService.McpServerInfo>> listServers() {
        return ResultVo.success(mcpAdminService.listServers());
    }

    @PostMapping("/servers/{name}/test")
    public ResultVo<McpAdminService.McpTestResult> test(@PathVariable("name") String name) {
        try {
            return ResultVo.success(mcpAdminService.testConnection(name));
        }
        catch (Exception e) {
            return ResultVo.error(e.getMessage());
        }
    }

    @GetMapping("/servers/{name}/tools")
    public ResultVo<List<McpAdminService.McpToolInfo>> tools(@PathVariable("name") String name) {
        try {
            return ResultVo.success(mcpAdminService.listTools(name));
        }
        catch (Exception e) {
            return ResultVo.error(e.getMessage());
        }
    }
}
