package com.zwiki.service.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class TokenCryptoService {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    private final SecretKey secretKey;
    private final boolean enabled;

    public TokenCryptoService(@Value("${zwiki.auth.token-crypto-key:}") String key) {
        if (!StringUtils.hasText(key)) {
            this.enabled = false;
            this.secretKey = null;
            log.warn("zwiki.auth.token-crypto-key 未配置，将以明文方式存储 GitHub access_token（不推荐）");
            return;
        }
        this.enabled = true;
        this.secretKey = buildKey(key.trim());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encryptIfEnabled(String plaintext) {
        if (!enabled) {
            return plaintext;
        }
        return encrypt(plaintext);
    }

    public String decryptIfEncrypted(String stored) {
        if (!StringUtils.hasText(stored)) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        if (!enabled) {
            log.warn("检测到加密 token，但未配置 zwiki.auth.token-crypto-key，无法解密");
            return null;
        }
        return decrypt(stored);
    }

    private String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ct, 0, payload, iv.length, ct.length);

            String b64 = Base64.getEncoder().encodeToString(payload);
            return PREFIX + b64;
        } catch (Exception e) {
            log.error("Token加密失败：{}", e.getMessage(), e);
            return null;
        }
    }

    private String decrypt(String stored) {
        try {
            String b64 = stored.substring(PREFIX.length());
            byte[] payload = Base64.getDecoder().decode(b64);
            if (payload.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[payload.length - IV_LEN];
            System.arraycopy(payload, 0, iv, 0, IV_LEN);
            System.arraycopy(payload, IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Token解密失败：{}", e.getMessage());
            return null;
        }
    }

    private SecretKey buildKey(String key) {
        byte[] raw = tryBase64(key);
        if (raw == null || !(raw.length == 16 || raw.length == 24 || raw.length == 32)) {
            raw = sha256(key);
        }
        return new SecretKeySpec(raw, "AES");
    }

    private byte[] tryBase64(String key) {
        try {
            return Base64.getDecoder().decode(key);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
