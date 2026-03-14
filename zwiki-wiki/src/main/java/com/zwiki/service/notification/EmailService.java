package com.zwiki.service.notification;
import com.zwiki.common.enums.RedisKeyEnum;
import com.zwiki.config.MailProperties;
import com.zwiki.util.RedisUtil;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件发送服务实现（品牌化HTML模板，异步发送，失败回退系统消息）
 *
 * @author zwiki
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final NotificationService notificationService;
    private final RedisUtil redisUtil;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    public EmailService(JavaMailSender mailSender, MailProperties mailProperties,
                        NotificationService notificationService, RedisUtil redisUtil) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.notificationService = notificationService;
        this.redisUtil = redisUtil;
    }

    public boolean isEnabled() {
        return mailProperties.isEnabled()
                && StringUtils.hasText(mailProperties.getBrandName());
    }

    @Async
    public void sendTaskCompletedEmail(String toEmail, String userId, String projectName, String taskId) {
        String subject = String.format("【%s】项目「%s」分析完成", mailProperties.getBrandName(), projectName);
        String body = buildBrandedHtml(
                "项目分析完成",
                String.format("您的项目 <strong>%s</strong> 已成功完成分析！", escapeHtml(projectName)),
                "点击下方按钮查看分析结果。",
                buildTaskUrl(taskId),
                "查看结果"
        );
        doSend(toEmail, userId, taskId, subject, body);
    }

    @Async
    public void sendTaskFailedEmail(String toEmail, String userId, String projectName, String taskId, String reason) {
        String subject = String.format("【%s】项目「%s」分析失败", mailProperties.getBrandName(), projectName);
        String safeReason = StringUtils.hasText(reason) ? escapeHtml(reason) : "未知错误";
        String body = buildBrandedHtml(
                "项目分析失败",
                String.format("您的项目 <strong>%s</strong> 分析过程中出现了问题。", escapeHtml(projectName)),
                "失败原因：" + safeReason + "<br/><br/>您可以尝试重新分析，或联系管理员获取帮助。",
                buildTaskUrl(taskId),
                "查看详情"
        );
        doSend(toEmail, userId, taskId, subject, body);
    }

    @Async
    public void sendQueueTimeoutEmail(String toEmail, String userId, String projectName, String taskId, int waitMinutes) {
        String subject = String.format("【%s】项目「%s」排队等待提醒", mailProperties.getBrandName(), projectName);
        String body = buildBrandedHtml(
                "排队等待提醒",
                String.format("您的项目 <strong>%s</strong> 已在队列中等待超过 <strong>%d 分钟</strong>。",
                        escapeHtml(projectName), waitMinutes),
                "当前系统处理繁忙，请耐心等待。任务完成后会第一时间通知您。",
                buildTaskUrl(taskId),
                "查看任务"
        );
        doSend(toEmail, userId, taskId, subject, body);
    }

    @Async
    public void sendTestEmail(String toEmail, String userId) {
        String subject = String.format("【%s】邮件通知测试", mailProperties.getBrandName());
        String body = buildBrandedHtml(
                "测试邮件",
                "恭喜！您的邮件通知配置正常工作。",
                "今后当您的项目分析完成或出现异常时，系统会自动发送邮件通知到此邮箱。",
                mailProperties.getHomeUrl(),
                "前往首页"
        );
        doSend(toEmail, userId, null, subject, body);
    }

    // ==================== 内部方法 ====================

    private void doSend(String toEmail, String userId, String taskId, String subject, String htmlBody) {
        if (!isEnabled()) {
            log.debug("邮件功能未启用，跳过发送");
            return;
        }
        if (!StringUtils.hasText(toEmail)) {
            log.debug("收件邮箱为空，跳过发送");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String from = mailProperties.getBrandName() + " <" + getFromAddress() + ">";
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("邮件发送成功: to={}, subject={}", toEmail, subject);
        } catch (Exception e) {
            log.error("邮件发送失败: to={}, subject={}, error={}", toEmail, subject, e.getMessage());
            handleSendFailure(userId, taskId, toEmail);
        }
    }

    /**
     * 邮件发送失败时，向用户发系统消息提醒检查邮箱（同一任务去重）
     */
    private void handleSendFailure(String userId, String taskId, String toEmail) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        try {
            String dedupKey = RedisKeyEnum.MAIL_FAIL_DEDUP.getKey(userId, taskId != null ? taskId : "test");
            if (Boolean.TRUE.equals(redisUtil.hasKey(dedupKey))) {
                return; // 已经提醒过
            }
            redisUtil.set(dedupKey, "1", RedisKeyEnum.MAIL_FAIL_DEDUP.getExpireTime());

            notificationService.sendSystemNotification(
                    userId,
                    "邮件发送失败",
                    String.format("系统尝试向您的邮箱 %s 发送通知邮件失败。请检查邮箱地址是否正确（特别是QQ邮箱格式），并确保已开启SMTP服务。您可以在「个人设置」中修改邮箱地址。",
                            toEmail)
            );
        } catch (Exception ignore) {
            log.warn("发送邮件失败提醒时出错: userId={}", userId);
        }
    }

    private String getFromAddress() {
        return StringUtils.hasText(mailUsername) ? mailUsername : "";
    }

    private String buildTaskUrl(String taskId) {
        String base = mailProperties.getHomeUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/repo/" + taskId;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 构建品牌化HTML邮件模板
     */
    private String buildBrandedHtml(String heading, String mainText, String secondaryText,
                                     String ctaUrl, String ctaLabel) {
        String brandName = escapeHtml(mailProperties.getBrandName());
        String primaryColor = mailProperties.getPrimaryColor();
        String logoUrl = mailProperties.getLogoUrl();
        String homeUrl = mailProperties.getHomeUrl();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"margin:0;padding:0;background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;\">");
        sb.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f5f5f5;padding:40px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,0.08);overflow:hidden;\">");

        // Header
        sb.append("<tr><td style=\"background:").append(primaryColor).append(";padding:24px 32px;text-align:center;\">");
        if (StringUtils.hasText(logoUrl)) {
            sb.append("<img src=\"").append(escapeHtml(logoUrl)).append("\" alt=\"").append(brandName)
                    .append("\" style=\"max-height:40px;margin-bottom:8px;display:block;margin-left:auto;margin-right:auto;\"/>");
        }
        sb.append("<span style=\"color:#ffffff;font-size:20px;font-weight:600;\">").append(brandName).append("</span>");
        sb.append("</td></tr>");

        // Body
        sb.append("<tr><td style=\"padding:32px;\">");
        sb.append("<h2 style=\"margin:0 0 16px;color:#1a1a1a;font-size:22px;\">").append(escapeHtml(heading)).append("</h2>");
        sb.append("<p style=\"margin:0 0 12px;color:#333;font-size:15px;line-height:1.6;\">").append(mainText).append("</p>");
        sb.append("<p style=\"margin:0 0 24px;color:#666;font-size:14px;line-height:1.6;\">").append(secondaryText).append("</p>");

        // CTA Button
        if (StringUtils.hasText(ctaUrl) && StringUtils.hasText(ctaLabel)) {
            sb.append("<div style=\"text-align:center;margin:24px 0;\">");
            sb.append("<a href=\"").append(escapeHtml(ctaUrl)).append("\" ");
            sb.append("style=\"display:inline-block;padding:12px 32px;background:").append(primaryColor);
            sb.append(";color:#ffffff;text-decoration:none;border-radius:6px;font-size:15px;font-weight:500;\">");
            sb.append(escapeHtml(ctaLabel)).append("</a>");
            sb.append("</div>");
        }

        sb.append("</td></tr>");

        // Footer
        sb.append("<tr><td style=\"padding:16px 32px;background:#fafafa;border-top:1px solid #f0f0f0;text-align:center;\">");
        sb.append("<p style=\"margin:0;color:#999;font-size:12px;\">");
        sb.append("此邮件由 <a href=\"").append(escapeHtml(homeUrl)).append("\" style=\"color:").append(primaryColor).append(";text-decoration:none;\">")
                .append(brandName).append("</a> 自动发送。");
        sb.append("如不想接收此类邮件，请在个人设置中关闭邮件通知。");
        sb.append("</p>");
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }
}
