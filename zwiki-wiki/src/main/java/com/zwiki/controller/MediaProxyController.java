package com.zwiki.controller;

import com.zwiki.common.result.ResultVo;
import com.zwiki.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * @author pai
 * @description: 媒体代理控制器：将外部媒体URL（视频/图片）下载后上传到MinIO，返回MinIO链接，解决OSS防盗链等跨域问题
 * @date 2026/1/30
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaProxyController {

    private static final long MAX_VIDEO_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 60_000;

    private final MinioService minioService;

    @PostMapping("/proxy-upload")
    public ResultVo<String> proxyUpload(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResultVo.error("url参数不能为空");
        }

        if (!minioService.isEnabled()) {
            return ResultVo.error("MinIO未配置，无法代理上传");
        }

        try {
            String extension = guessExtension(url);
            String contentType = guessContentType(extension);

            log.info("Media proxy: downloading from external URL, extension={}", extension);

            // 尝试多种策略绕过 OSS 防盗链
            URI uri = URI.create(url);
            String origin = uri.getScheme() + "://" + uri.getHost();
            
            // 策略列表：[Referer, Origin, Accept] - null表示不设置该header
            String[][] strategies = {
                // 策略1: 不带Referer，模拟直接访问（OSS签名URL通常允许）
                {null, null, "image/*,video/*,*/*"},
                // 策略2: 空Referer
                {"", null, "image/*,video/*,*/*"},
                // 策略3: 带Origin头
                {null, origin, "*/*"},
                // 策略4: DashScope Referer
                {"https://dashscope.aliyuncs.com/", null, "*/*"},
                // 策略5: 阿里云控制台Referer
                {"https://home.console.aliyun.com/", null, "*/*"},
                // 策略6: OSS自身Origin
                {origin + "/", origin, "*/*"},
            };

            HttpURLConnection conn = null;
            int status = 0;
            int strategyIndex = 0;
            for (String[] strategy : strategies) {
                strategyIndex++;
                String referer = strategy[0];
                String originHeader = strategy[1];
                String accept = strategy[2];
                
                conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", accept != null ? accept : "*/*");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                conn.setRequestProperty("Cache-Control", "no-cache");
                if (referer != null) {
                    conn.setRequestProperty("Referer", referer);
                }
                if (originHeader != null) {
                    conn.setRequestProperty("Origin", originHeader);
                }
                conn.connect();
                status = conn.getResponseCode();
                if (status == 200) {
                    log.info("Media proxy: download succeeded with strategy #{}", strategyIndex);
                    break;
                }
                log.warn("Media proxy: got {} with strategy #{} (Referer={}, Origin={}), trying next...", 
                         status, strategyIndex, referer, originHeader);
                conn.disconnect();
            }
            if (status != 200) {
                // 如果所有策略都失败，可能是URL已过期（OSS签名URL有时效）
                return ResultVo.error("下载失败(HTTP " + status + ")，可能是链接已过期或有访问限制");
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_VIDEO_SIZE) {
                conn.disconnect();
                return ResultVo.error("文件过大，超过100MB限制");
            }

            // 从响应头获取更准确的content-type
            String respCt = conn.getContentType();
            if (respCt != null && !respCt.isBlank()) {
                contentType = respCt.split(";")[0].trim();
            }

            byte[] bytes;
            try (InputStream is = conn.getInputStream()) {
                bytes = is.readAllBytes();
            } finally {
                conn.disconnect();
            }

            if (bytes.length == 0) {
                return ResultVo.error("下载的文件为空");
            }
            if (bytes.length > MAX_VIDEO_SIZE) {
                return ResultVo.error("文件过大，超过100MB限制");
            }

            log.info("Media proxy: downloaded {} bytes, uploading to MinIO...", bytes.length);

            String minioUrl = minioService.uploadBytes("chat-media", bytes, contentType, extension);
            log.info("Media proxy: uploaded to MinIO -> {}", minioUrl);

            return ResultVo.success(minioUrl);
        } catch (Exception e) {
            log.error("Media proxy failed: url={}, error={}", url, e.getMessage(), e);
            return ResultVo.error("代理上传失败: " + e.getMessage());
        }
    }

    private String guessExtension(String url) {
        try {
            String path = URI.create(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot > 0) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.length() <= 5) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return "mp4";
    }

    private String guessContentType(String extension) {
        return switch (extension) {
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "m4v" -> "video/x-m4v";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
