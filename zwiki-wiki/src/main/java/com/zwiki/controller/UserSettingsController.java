package com.zwiki.controller;

import com.zwiki.util.AuthUtil;
import com.zwiki.common.result.ResultVo;
import com.zwiki.repository.entity.ZwikiUser;
import com.zwiki.repository.dao.ZwikiUserRepository;
import com.zwiki.service.notification.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @author pai
 * @description: 用户设置控制器
 * @date 2026/1/26
 */
@RestController
@RequestMapping("/api/auth/settings")
public class UserSettingsController {

    @Autowired
    private ZwikiUserRepository userMapper;

    @Autowired
    private EmailService emailService;

    @GetMapping
    public ResultVo<Map<String, Object>> getSettings() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        ZwikiUser user = userMapper.findFirstByUserId(userId).orElse(null);
        if (user == null) {
            return ResultVo.error("用户不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getUserId());
        data.put("displayName", user.getDisplayName());
        data.put("email", user.getEmail());
        data.put("avatarUrl", user.getAvatarUrl());
        data.put("role", user.getRole());
        data.put("catalogueModel", user.getCatalogueModel());
        data.put("docGenModel", user.getDocGenModel());
        data.put("chatModel", user.getChatModel());
        data.put("assistantModel", user.getAssistantModel());
        data.put("notificationEnabled", user.getNotificationEnabled() != null ? user.getNotificationEnabled() : true);
        data.put("emailNotificationEnabled", user.getEmailNotificationEnabled() != null ? user.getEmailNotificationEnabled() : false);
        data.put("preferences", user.getPreferences());
        data.put("lastLoginTime", user.getLastLoginTime());
        data.put("createTime", user.getCreateTime());
        return ResultVo.success(data);
    }

    @PutMapping
    public ResultVo<String> updateSettings(@RequestBody Map<String, Object> body) {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }

        ZwikiUser user = userMapper.findFirstByUserId(userId).orElse(null);
        if (user == null) {
            return ResultVo.error("用户不存在");
        }

        if (body.containsKey("displayName")) {
            String displayName = String.valueOf(body.get("displayName"));
            if (StringUtils.hasText(displayName)) {
                user.setDisplayName(displayName.trim());
            }
        }
        if (body.containsKey("email")) {
            Object emailObj = body.get("email");
            user.setEmail(emailObj != null ? String.valueOf(emailObj).trim() : null);
        }
        if (body.containsKey("catalogueModel")) {
            Object v = body.get("catalogueModel");
            user.setCatalogueModel(v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null);
        }
        if (body.containsKey("docGenModel")) {
            Object v = body.get("docGenModel");
            user.setDocGenModel(v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null);
        }
        if (body.containsKey("chatModel")) {
            Object v = body.get("chatModel");
            user.setChatModel(v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null);
        }
        if (body.containsKey("assistantModel")) {
            Object v = body.get("assistantModel");
            user.setAssistantModel(v != null && StringUtils.hasText(String.valueOf(v)) ? String.valueOf(v).trim() : null);
        }
        if (body.containsKey("notificationEnabled")) {
            Object ne = body.get("notificationEnabled");
            if (ne instanceof Boolean b) {
                user.setNotificationEnabled(b);
            } else if (ne != null) {
                user.setNotificationEnabled(Boolean.parseBoolean(String.valueOf(ne)));
            }
        }
        if (body.containsKey("emailNotificationEnabled")) {
            Object ene = body.get("emailNotificationEnabled");
            if (ene instanceof Boolean b) {
                user.setEmailNotificationEnabled(b);
            } else if (ene != null) {
                user.setEmailNotificationEnabled(Boolean.parseBoolean(String.valueOf(ene)));
            }
        }
        if (body.containsKey("preferences")) {
            Object pref = body.get("preferences");
            user.setPreferences(pref != null ? String.valueOf(pref) : null);
        }

        userMapper.save(user);
        return ResultVo.success("设置已保存");
    }

    @PostMapping("/test-email")
    public ResultVo<String> sendTestEmail() {
        String userId = AuthUtil.getCurrentUserId();
        if (userId == null) {
            return ResultVo.error(401, "未登录");
        }
        ZwikiUser user = userMapper.findFirstByUserId(userId).orElse(null);
        if (user == null) {
            return ResultVo.error("用户不存在");
        }
        if (!StringUtils.hasText(user.getEmail())) {
            return ResultVo.error("请先设置邮箱地址");
        }
        if (!emailService.isEnabled()) {
            return ResultVo.error("邮件服务未启用，请联系管理员配置邮件服务");
        }
        emailService.sendTestEmail(user.getEmail(), userId);
        return ResultVo.success("测试邮件已发送，请查收");
    }
}
