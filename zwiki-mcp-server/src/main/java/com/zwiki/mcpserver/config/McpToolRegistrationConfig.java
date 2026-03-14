package com.zwiki.mcpserver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwiki.mcpserver.mcp.McpToolRegistry;
import com.zwiki.mcpserver.mcp.McpToolRegistry.ParamDef;
import com.zwiki.mcpserver.tool.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将所有 MCP 工具注册到 McpToolRegistry
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpToolRegistrationConfig {

    private final McpToolRegistry registry;
    private final ProjectTool projectTool;
    private final WikiSearchTool wikiSearchTool;
    private final RepoStructureTool repoStructureTool;
    private final FileReaderTool fileReaderTool;
    private final WikiDocumentTool wikiDocumentTool;
    private final ProjectReportTool projectReportTool;

    @PostConstruct
    public void registerAllTools() {
        // 1. list_projects
        Map<String, ParamDef> listProjectsParams = new LinkedHashMap<>();
        listProjectsParams.put("keyword", new ParamDef("可选，按项目名称过滤的关键词", false));
        registry.register("list_projects",
                "列出 ZwikiAI 平台上所有已分析的代码仓库/项目。可按关键词过滤项目名称。返回项目列表包含 taskId（后续工具调用需要）、项目名、仓库地址、分析状态等信息。",
                listProjectsParams,
                args -> projectTool.list_projects(textParam(args, "keyword")));

        // 2. search_wiki
        Map<String, ParamDef> searchWikiParams = new LinkedHashMap<>();
        searchWikiParams.put("query", new ParamDef("搜索关键词", true));
        searchWikiParams.put("project_name", new ParamDef("可选，按项目名称过滤", false));
        registry.register("search_wiki",
                "在 ZwikiAI 生成的 Wiki 文档中搜索关键词。可以搜索文档标题和内容。支持按项目名称过滤。返回匹配的文档片段和所属项目信息。",
                searchWikiParams,
                args -> wikiSearchTool.search_wiki(textParam(args, "query"), textParam(args, "project_name")));

        // 3. get_repo_structure
        Map<String, ParamDef> repoStructureParams = new LinkedHashMap<>();
        repoStructureParams.put("task_id", new ParamDef("项目的 taskId，可通过 list_projects 工具获取", true));
        repoStructureParams.put("sub_path", new ParamDef("可选，子目录路径（相对于项目根目录），如 src/main/java", false));
        registry.register("get_repo_structure",
                "查看已分析项目的文件目录结构。通过 taskId 指定项目，可选指定子目录路径。返回树形文件结构，帮助理解项目的模块划分和目录组织。",
                repoStructureParams,
                args -> repoStructureTool.get_repo_structure(textParam(args, "task_id"), textParam(args, "sub_path")));

        // 4. read_file
        Map<String, ParamDef> readFileParams = new LinkedHashMap<>();
        readFileParams.put("task_id", new ParamDef("项目的 taskId，可通过 list_projects 工具获取", true));
        readFileParams.put("file_path", new ParamDef("文件的相对路径（相对于项目根目录），如 src/main/java/com/example/App.java", true));
        registry.register("read_file",
                "读取已分析项目中的单个文件内容。用于查看核心文件的实现细节、进行代码审查或深入分析特定模块。需要提供 taskId 和相对文件路径。",
                readFileParams,
                args -> fileReaderTool.read_file(textParam(args, "task_id"), textParam(args, "file_path")));

        // 5. get_wiki_document
        Map<String, ParamDef> wikiDocParams = new LinkedHashMap<>();
        wikiDocParams.put("task_id", new ParamDef("项目的 taskId，可通过 list_projects 工具获取", true));
        wikiDocParams.put("title", new ParamDef("可选，文档标题关键词，用于定位特定章节", false));
        wikiDocParams.put("catalogue_id", new ParamDef("可选，目录节点 ID（catalogueId），精确获取某个文档节点", false));
        registry.register("get_wiki_document",
                "获取 ZwikiAI 为项目生成的 Wiki 文档内容。可以获取整个项目的文档概览，或通过标题/catalogueId 获取特定章节的详细内容。",
                wikiDocParams,
                args -> wikiDocumentTool.get_wiki_document(
                        textParam(args, "task_id"), textParam(args, "title"), textParam(args, "catalogue_id")));

        // 6. get_project_report
        Map<String, ParamDef> reportParams = new LinkedHashMap<>();
        reportParams.put("task_id", new ParamDef("项目的 taskId，可通过 list_projects 工具获取", true));
        registry.register("get_project_report",
                "获取项目的分析摘要报告，包括项目概述、技术栈、核心模块等关键信息。适合快速了解一个项目的全貌。",
                reportParams,
                args -> projectReportTool.get_project_report(textParam(args, "task_id")));

        log.info("已注册 {} 个 MCP 工具", registry.listTools().size());
    }

    private String textParam(JsonNode args, String key) {
        if (args == null || !args.has(key) || args.get(key).isNull()) {
            return null;
        }
        String value = args.get(key).asText();
        return value.isEmpty() ? null : value;
    }
}
