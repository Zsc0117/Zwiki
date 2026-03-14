package com.zwiki.llm.service;

/**
 * 用户ID提供者接口。
 * 用于在LLM负载均衡层获取当前用户ID，以便正确记录token用量。
 * 
 * 由于zwiki-llm模块不依赖具体的认证框架（如Sa-Token），
 * 通过此接口实现解耦，由上层模块（如zwiki-wiki）提供实现。
 * 
 * @author zwiki
 */
public interface UserIdProvider {
    
    /**
     * 获取当前线程/请求的用户ID。
     * 
     * @return 用户ID，如果用户未登录或无法获取则返回null
     */
    String getCurrentUserId();
}
