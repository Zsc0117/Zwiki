package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwiki.domain.dto.ReviewCommentDTO;
import com.zwiki.service.knowledge.RAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Style Convention Agent - checks code against coding standards
 */
@Component
public class StyleConventionAgent implements NodeAction {

    private static final Logger logger = LoggerFactory.getLogger(StyleConventionAgent.class);

    @Autowired
    private ChatClient chatClient;
    
    @Autowired
    private RAGService ragService;

    private static final Map<String, String> NAMING_RULES = new HashMap<>();
    private static final List<String> DEPRECATED_APIS = new ArrayList<>();
    private static final Map<String, String> CODE_SMELLS = new HashMap<>();

    static {
        NAMING_RULES.put("class", "^[A-Z][a-zA-Z0-9]*$");
        NAMING_RULES.put("method", "^[a-z][a-zA-Z0-9]*$");
        NAMING_RULES.put("constant", "^[A-Z][A-Z0-9_]*$");
        NAMING_RULES.put("variable", "^[a-z][a-zA-Z0-9]*$");
        NAMING_RULES.put("package", "^[a-z]+(\\.[a-z]+)*$");

        DEPRECATED_APIS.add("Date");
        DEPRECATED_APIS.add("Calendar");
        DEPRECATED_APIS.add("SimpleDateFormat");
        DEPRECATED_APIS.add("Vector");
        DEPRECATED_APIS.add("Hashtable");

        CODE_SMELLS.put("System.out.println", "Should use logging framework instead of System.out");
        CODE_SMELLS.put("printStackTrace\\(\\)", "Should use logging for exceptions instead of printStackTrace");
        CODE_SMELLS.put("throw new Exception", "Should throw specific exception types instead of generic Exception");
        CODE_SMELLS.put("catch\\s*\\(\\s*Exception", "Should catch specific exceptions instead of generic Exception");
        CODE_SMELLS.put("// TODO", "Contains unfinished TODO");
        CODE_SMELLS.put("// FIXME", "Contains FIXME that needs attention");
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("StyleConventionAgent started");

        String diffContent = (String) state.value("diff_content").orElse("");
        String[] changedFiles = (String[]) state.value("changed_files").orElse(new String[0]);
        String repoName = (String) state.value("repo_name").orElse("unknown");

        List<ReviewCommentDTO> styleIssues = new ArrayList<>();

        Map<String, List<String>> fileChanges = parseDiffByFile(diffContent);

        for (Map.Entry<String, List<String>> entry : fileChanges.entrySet()) {
            String filePath = entry.getKey();
            List<String> changes = entry.getValue();

            if (!isJavaFile(filePath)) {
                continue;
            }

            logger.debug("Checking style for file: {}", filePath);

            styleIssues.addAll(checkCodeSmells(filePath, changes));
            styleIssues.addAll(checkDeprecatedApis(filePath, changes));
            styleIssues.addAll(checkNamingConventions(filePath, changes));
        }

        styleIssues.addAll(performLlmStyleAnalysis(diffContent, repoName));

        logger.info("StyleConventionAgent completed, found {} issues", styleIssues.size());

        Map<String, Object> result = new HashMap<>();
        result.put("style_issues", styleIssues);
        result.put("style_issue_count", styleIssues.size());

        return result;
    }

    private Map<String, List<String>> parseDiffByFile(String diffContent) {
        Map<String, List<String>> result = new HashMap<>();
        String currentFile = null;
        List<String> currentChanges = new ArrayList<>();
        int lineNumber = 0;

        for (String line : diffContent.split("\n")) {
            if (line.startsWith("+++ b/")) {
                if (currentFile != null && !currentChanges.isEmpty()) {
                    result.put(currentFile, new ArrayList<>(currentChanges));
                }
                currentFile = line.substring(6);
                currentChanges = new ArrayList<>();
            } else if (line.startsWith("@@")) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    String newRange = parts[2];
                    if (newRange.startsWith("+")) {
                        String[] rangeParts = newRange.substring(1).split(",");
                        lineNumber = Integer.parseInt(rangeParts[0]);
                    }
                }
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                currentChanges.add(lineNumber + ":" + line.substring(1));
                lineNumber++;
            } else if (!line.startsWith("-")) {
                lineNumber++;
            }
        }

        if (currentFile != null && !currentChanges.isEmpty()) {
            result.put(currentFile, currentChanges);
        }

        return result;
    }

    private boolean isJavaFile(String filePath) {
        return filePath.toLowerCase().endsWith(".java");
    }

    private List<ReviewCommentDTO> checkCodeSmells(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();

        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;

            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];

            for (Map.Entry<String, String> smell : CODE_SMELLS.entrySet()) {
                if (Pattern.compile(smell.getKey()).matcher(code).find()) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment(smell.getValue())
                        .severity("warning")
                        .build());
                }
            }
        }

        return issues;
    }

    private List<ReviewCommentDTO> checkDeprecatedApis(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();

        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;

            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];

            for (String api : DEPRECATED_APIS) {
                if (code.contains(api)) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment(String.format("Consider using modern alternatives instead of %s", api))
                        .severity("info")
                        .build());
                }
            }
        }

        return issues;
    }

    private List<ReviewCommentDTO> checkNamingConventions(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();

        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;

            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1].trim();

            if (code.matches(".*class\\s+[a-z].*")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Class names should start with uppercase letter")
                    .severity("warning")
                    .build());
            }

            if (code.matches(".*static\\s+final\\s+\\w+\\s+[a-z].*")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Constants should be in UPPER_SNAKE_CASE")
                    .severity("info")
                    .build());
            }
        }

        return issues;
    }

    private List<ReviewCommentDTO> performLlmStyleAnalysis(String diffContent, String repoName) {
        List<ReviewCommentDTO> issues = new ArrayList<>();

        try {
            String codeSnippet = diffContent.lines()
                .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
                .limit(80)
                .reduce("", (a, b) -> a + "\n" + b.substring(1));

            if (codeSnippet.trim().isEmpty()) {
                return issues;
            }

            String prompt = String.format(
                "As a code style expert, analyze the following code for style and convention issues:\n\n" +
                "%s\n\n" +
                "List up to 3 most important style issues (if any), format:\n" +
                "1. [Issue Type] Description\n" +
                "If code follows good conventions, return: No style issues found",
                codeSnippet
            );

            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            if (!response.contains("No style issues found") && !response.trim().isEmpty()) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath("general")
                    .lineNumber(0)
                    .comment("LLM Style Analysis: " + response.substring(0, Math.min(response.length(), 300)))
                    .severity("info")
                    .build());
            }

        } catch (Exception e) {
            logger.error("LLM style analysis failed", e);
        }

        return issues;
    }
}
