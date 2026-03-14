package com.zwiki.service;

import com.zwiki.service.auth.SaTokenUserContext;
import com.zwiki.llm.service.UserIdProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 基于SaTokenUserContext的用户ID提供者实现。
 * 从ThreadLocal中获取当前请求的用户ID。
 * 
 * @author zwiki
 */
@Slf4j
@Component
public class SaTokenUserIdProvider implements UserIdProvider {

    @Override
    public String getCurrentUserId() {
        String userId = SaTokenUserContext.getCurrentUserId();
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        log.debug("SaTokenUserIdProvider: 无法获取当前用户ID (ThreadLocal为空)");
        return null;
    }
}
