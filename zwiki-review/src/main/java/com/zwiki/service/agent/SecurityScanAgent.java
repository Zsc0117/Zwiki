package com.zwiki.service.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwiki.domain.dto.ReviewCommentDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全漏洞扫描Agent - 基于安全规则库检测潜在安全风险
 */
@Component
public class SecurityScanAgent implements NodeAction {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityScanAgent.class);
    
    @Autowired
    private ChatClient chatClient;
    
    private static final List<Pattern> SQL_INJECTION_PATTERNS = List.of(
        Pattern.compile("\"[^\"]*\\+.*\\+[^\"]*\""),
        Pattern.compile("\\$\\{.*\\}"),
        Pattern.compile("PreparedStatement.*\\+"),
        Pattern.compile("Statement.*executeQuery\\(.*\\+"),
        Pattern.compile("createQuery\\(.*\\+")
    );
    
    private static final List<Pattern> XSS_PATTERNS = List.of(
        Pattern.compile("response\\.getWriter\\(\\)\\.print.*\\+"),
        Pattern.compile("out\\.print.*\\+.*request\\.getParameter"),
        Pattern.compile("innerHTML.*\\+"),
        Pattern.compile("document\\.write.*\\+")
    );
    
    private static final List<Pattern> HARDCODED_SECRET_PATTERNS = List.of(
        Pattern.compile("(password|passwd|pwd)\\s*=\\s*[\"'][^\"']{3,}[\"']", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(secret|key|token)\\s*=\\s*[\"'][^\"']{10,}[\"']", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(api[_-]?key|access[_-]?token)\\s*=\\s*[\"'][^\"']+[\"']", Pattern.CASE_INSENSITIVE),
        Pattern.compile("[\"'][A-Za-z0-9]{20,}[\"']")
    );
    
    private static final List<String> INSECURE_ALGORITHMS = List.of(
        "MD5", "SHA1", "DES", "RC4", "MD2"
    );
    
    private static final List<String> INSECURE_RANDOM = List.of(
        "Math.random()", "Random()", "new Random("
    );
    
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        logger.info("SecurityScanAgent started");
        
        String diffContent = (String) state.value("diff_content").orElse("");
        String[] changedFiles = (String[]) state.value("changed_files").orElse(new String[0]);
        
        List<ReviewCommentDTO> securityIssues = new ArrayList<>();
        Map<String, List<String>> fileChanges = parseDiffByFile(diffContent);
        
        for (Map.Entry<String, List<String>> entry : fileChanges.entrySet()) {
            String filePath = entry.getKey();
            List<String> changes = entry.getValue();
            
            if (!isCodeFile(filePath)) {
                continue;
            }
            
            logger.debug("Scanning file: {}", filePath);
            
            securityIssues.addAll(checkSQLInjectionRisks(filePath, changes));
            securityIssues.addAll(checkXSSRisks(filePath, changes));
            securityIssues.addAll(checkHardcodedSecrets(filePath, changes));
            securityIssues.addAll(checkInsecureCrypto(filePath, changes));
            securityIssues.addAll(checkAuthenticationIssues(filePath, changes));
            securityIssues.addAll(checkFileOperationSecurity(filePath, changes));
            securityIssues.addAll(checkDeserializationSecurity(filePath, changes));
        }
        
        securityIssues.addAll(performAdvancedSecurityAnalysis(diffContent));
        
        long criticalCount = securityIssues.stream().filter(i -> "error".equals(i.severity())).count();
        long highCount = securityIssues.stream().filter(i -> "warning".equals(i.severity())).count();
        long mediumCount = securityIssues.stream().filter(i -> "info".equals(i.severity())).count();
        
        String riskLevel = calculateSecurityRiskLevel(criticalCount, highCount, mediumCount);
        
        logger.info("SecurityScanAgent completed, found {} issues, risk level: {}", securityIssues.size(), riskLevel);
        
        Map<String, Object> result = new HashMap<>();
        result.put("security_issues", securityIssues);
        result.put("security_risk_level", riskLevel);
        result.put("critical_count", criticalCount);
        result.put("high_count", highCount);
        result.put("medium_count", mediumCount);
        
        return result;
    }
    
    private Map<String, List<String>> parseDiffByFile(String diffContent) {
        Map<String, List<String>> result = new HashMap<>();
        String currentFile = null;
        List<String> currentChanges = new ArrayList<>();
        int lineNumber = 0;
        
        for (String line : diffContent.split("\n")) {
            if (line.startsWith("diff --git") || line.startsWith("--- ") || line.startsWith("+++ ")) {
                if (line.startsWith("+++ b/")) {
                    if (currentFile != null && !currentChanges.isEmpty()) {
                        result.put(currentFile, new ArrayList<>(currentChanges));
                    }
                    currentFile = line.substring(6);
                    currentChanges = new ArrayList<>();
                }
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
    
    private boolean isCodeFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".js") ||
               lower.endsWith(".ts") || lower.endsWith(".py") || lower.endsWith(".go") ||
               lower.endsWith(".rb") || lower.endsWith(".php") || lower.endsWith(".cs") ||
               lower.endsWith(".cpp") || lower.endsWith(".c") || lower.endsWith(".h");
    }
    
    private List<ReviewCommentDTO> checkSQLInjectionRisks(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            for (Pattern pattern : SQL_INJECTION_PATTERNS) {
                if (pattern.matcher(code).find()) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment("Potential SQL injection risk, use parameterized queries")
                        .severity("error")
                        .build());
                    break;
                }
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkXSSRisks(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            for (Pattern pattern : XSS_PATTERNS) {
                if (pattern.matcher(code).find()) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment("Potential XSS risk, sanitize user input before output")
                        .severity("error")
                        .build());
                    break;
                }
            }
            
            if (code.contains("${") && filePath.endsWith(".jsp")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("JSTL expression may have XSS risk, use fn:escapeXml for escaping")
                    .severity("warning")
                    .build());
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkHardcodedSecrets(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            for (Pattern pattern : HARDCODED_SECRET_PATTERNS) {
                Matcher matcher = pattern.matcher(code);
                if (matcher.find()) {
                    String matched = matcher.group();
                    if (!isExampleValue(matched)) {
                        issues.add(ReviewCommentDTO.builder()
                            .filePath(filePath)
                            .lineNumber(lineNumber)
                            .comment("Suspected hardcoded secret, use config files or environment variables")
                            .severity("error")
                            .build());
                    }
                }
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkInsecureCrypto(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            for (String algorithm : INSECURE_ALGORITHMS) {
                if (code.contains(algorithm)) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment(String.format("Insecure algorithm %s used, use SHA-256 or stronger", algorithm))
                        .severity("warning")
                        .build());
                }
            }
            
            for (String random : INSECURE_RANDOM) {
                if (code.contains(random)) {
                    issues.add(ReviewCommentDTO.builder()
                        .filePath(filePath)
                        .lineNumber(lineNumber)
                        .comment("Insecure random generator, use SecureRandom for security-sensitive scenarios")
                        .severity("info")
                        .build());
                }
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkAuthenticationIssues(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            if (code.contains("permitAll()") || code.contains("anonymous()")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Authentication bypass detected, confirm if public access is intended")
                    .severity("info")
                    .build());
            }
            
            if (code.matches(".*['\"][a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}['\"].*")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Hardcoded email address, may be test code residue")
                    .severity("info")
                    .build());
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkFileOperationSecurity(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            if (code.contains("..") && (code.contains("File") || code.contains("Path"))) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Potential path traversal attack risk, validate file path")
                    .severity("error")
                    .build());
            }
            
            if (code.contains("MultipartFile") && !code.contains("getOriginalFilename")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("File upload should validate file type and size")
                    .severity("warning")
                    .build());
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> checkDeserializationSecurity(String filePath, List<String> changes) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        for (String change : changes) {
            String[] parts = change.split(":", 2);
            if (parts.length < 2) continue;
            
            int lineNumber = Integer.parseInt(parts[0]);
            String code = parts[1];
            
            if (code.contains("ObjectInputStream") || code.contains("readObject")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("Java deserialization has security risks, avoid deserializing untrusted data")
                    .severity("error")
                    .build());
            }
            
            if (code.contains("JSON.parseObject") || code.contains("JSON.parse")) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath(filePath)
                    .lineNumber(lineNumber)
                    .comment("FastJson deserialization has known vulnerabilities, consider using Jackson")
                    .severity("warning")
                    .build());
            }
        }
        
        return issues;
    }
    
    private List<ReviewCommentDTO> performAdvancedSecurityAnalysis(String diffContent) {
        List<ReviewCommentDTO> issues = new ArrayList<>();
        
        try {
            String codeSnippet = diffContent.lines()
                .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
                .limit(100)
                .reduce("", (a, b) -> a + "\n" + b.substring(1));
            
            if (codeSnippet.trim().isEmpty()) {
                return issues;
            }
            
            String prompt = String.format(
                "As a security expert, analyze the following code changes for security risks based on OWASP Top 10:\n\n" +
                "%s\n\n" +
                "List the most serious security issues (if any), format:\n" +
                "1. [Risk Type] Specific problem description\n" +
                "If no obvious security issues, return: No obvious security risks",
                codeSnippet
            );
            
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            
            if (!response.contains("No obvious security risks") && !response.trim().isEmpty()) {
                issues.add(ReviewCommentDTO.builder()
                    .filePath("general")
                    .lineNumber(0)
                    .comment("LLM Security Analysis: " + response.substring(0, Math.min(response.length(), 300)))
                    .severity("info")
                    .build());
            }
            
        } catch (Exception e) {
            logger.error("LLM security analysis failed", e);
        }
        
        return issues;
    }
    
    private boolean isExampleValue(String value) {
        String lower = value.toLowerCase();
        return lower.contains("example") || lower.contains("test") || 
               lower.contains("demo") || lower.contains("123456") ||
               lower.contains("password") || lower.equals("\"\"") ||
               lower.equals("''") || lower.contains("your_");
    }
    
    private String calculateSecurityRiskLevel(long critical, long high, long medium) {
        if (critical > 0) {
            return "CRITICAL";
        } else if (high >= 3) {
            return "HIGH";
        } else if (high > 0 || medium >= 5) {
            return "MEDIUM";
        } else if (medium > 0) {
            return "LOW";
        } else {
            return "SAFE";
        }
    }
}
