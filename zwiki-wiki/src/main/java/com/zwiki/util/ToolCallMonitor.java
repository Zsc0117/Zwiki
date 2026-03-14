package com.zwiki.util;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author pai
 * @description: Tool Calling 监控切面
 * @date 2026/1/20
 */
@Aspect
@Component
@Slf4j
@ConditionalOnProperty(name = "project.wiki.monitor.tool-calling", havingValue = "true", matchIfMissing = true)
public class ToolCallMonitor {
    
    private final AtomicLong toolCallCounter = new AtomicLong(0);
    
    @Pointcut("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public void toolMethod() {}
    
    @Around("toolMethod()")
    public Object monitorToolCalling(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        String toolName = toolAnnotation.name();
        
        Object[] args = joinPoint.getArgs();
        long callId = toolCallCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        log.info("🔧 Tool调用开始 [{}]: toolName={}, parameters={}", 
                callId, toolName, formatParameters(args));
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            String resultStr = result != null ? result.toString() : "null";
            log.info("✅ Tool调用成功 [{}]: toolName={}, 耗时={}ms, 结果长度={}", 
                    callId, toolName, duration, resultStr.length());
            
            // 如果结果很长，只记录前500字符
            if (resultStr.length() > 500) {
                log.debug("Tool调用结果预览 [{}]: {}", callId, resultStr.substring(0, 500) + "...");
            } else {
                log.debug("Tool调用完整结果 [{}]: {}", callId, resultStr);
            }
            
            // 统计信息
            recordToolCallStats(toolName, duration, true);
            
            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("❌ Tool调用失败 [{}]: toolName={}, 耗时={}ms, 错误={}", 
                     callId, toolName, duration, e.getMessage(), e);
            
            recordToolCallStats(toolName, duration, false);
            throw e;
        }
    }
    
    private String formatParameters(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            if (arg instanceof String && ((String) arg).length() > 100) {
                sb.append("\"").append(((String) arg).substring(0, 100)).append("...\"");
            } else {
                sb.append(arg);
            }
        }
        sb.append("]");
        return sb.toString();
    }
    
    private void recordToolCallStats(String toolName, long duration, boolean success) {
        // 可以在这里记录更详细的统计信息
        log.info("📊 Tool统计: toolName={}, duration={}ms, success={}, totalCalls={}", 
                toolName, duration, success, toolCallCounter.get());
    }
}