package com.zwiki.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件通知配置属性
 *
 * @author zwiki
 */
@Data
@Component
@ConfigurationProperties(prefix = "zwiki.mail")
public class MailProperties {

    /**
     * 是否启用邮件通知
     */
    private boolean enabled = false;

    /**
     * 品牌名称
     */
    private String brandName = "ZwikiAI";

    /**
     * Logo URL（可公网访问的HTTPS地址）
     */
    private String logoUrl;

    /**
     * 主题色（十六进制）
     */
    private String primaryColor = "#1677ff";

    /**
     * 前端首页URL
     */
    private String homeUrl = "http://localhost:3000";

    /**
     * 排队超时阈值（分钟）
     */
    private int queueTimeoutMinutes = 15;

    /**
     * 排队超时提醒冷却（分钟）
     */
    private int queueReminderCooldownMinutes = 60;
}
