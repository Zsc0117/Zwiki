package com.zwiki.service.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.StringUtils;
import org.springframework.util.SerializationUtils;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpCookieOAuth2AuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpCookieOAuth2AuthorizationRequestRepository.class);

    private static final String COOKIE_NAME = "ZWIKI_OAUTH2_AUTH_REQUEST";
    public static final String BIND_MODE_COOKIE = "ZWIKI_OAUTH_BIND_MODE";
    public static final String BIND_USER_ID_COOKIE = "ZWIKI_OAUTH_BIND_USER_ID";
    public static final String BIND_KEY_COOKIE = "ZWIKI_OAUTH_BIND_KEY";
    private static final String BIND_USER_ID_ATTR = "ZWIKI_BIND_USER_ID";
    private static final String BIND_KEY_ATTR = "ZWIKI_BIND_KEY";
    private static final ThreadLocal<BindContext> BIND_CONTEXT_HOLDER = new ThreadLocal<>();

    public static final class BindContext {
        private final String bindUserId;
        private final String bindKey;

        public BindContext(String bindUserId, String bindKey) {
            this.bindUserId = bindUserId;
            this.bindKey = bindKey;
        }

        public String getBindUserId() {
            return bindUserId;
        }

        public String getBindKey() {
            return bindKey;
        }
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = getCookie(request, COOKIE_NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            BIND_CONTEXT_HOLDER.remove();
            return null;
        }
        OAuth2AuthorizationRequest authorizationRequest = deserialize(cookie.getValue());
        cacheBindContext(request, authorizationRequest);
        return authorizationRequest;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }

        OAuth2AuthorizationRequest requestToSave = authorizationRequest;
        String bindUserId = getBindUserId(request);
        String bindKey = getBindKey(request);
        log.info("[saveAuthorizationRequest] 检查绑定Cookie: bindUserId={}, bindKey={}, requestCookies={}", 
                bindUserId, bindKey, request.getCookies() != null ? request.getCookies().length : 0);
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (c.getName().startsWith("ZWIKI")) {
                    log.debug("[saveAuthorizationRequest] Cookie: {}={}", c.getName(), c.getValue());
                }
            }
        }
        if (bindUserId != null && !bindUserId.isBlank()) {
            log.info("[saveAuthorizationRequest] 嵌入bindUserId和bindKey到OAuth请求: userId={}, key={}", bindUserId, bindKey);
            final String finalBindKey = bindKey;
            requestToSave = OAuth2AuthorizationRequest.from(authorizationRequest)
                    .attributes(attrs -> {
                        attrs.put(BIND_USER_ID_ATTR, bindUserId);
                        if (finalBindKey != null) {
                            attrs.put(BIND_KEY_ATTR, finalBindKey);
                        }
                    })
                    .build();
        }

        String serialized = serialize(requestToSave);
        Cookie cookie = new Cookie(COOKIE_NAME, serialized);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(300);
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest removed = loadAuthorizationRequest(request);
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return removed;
    }

    private Cookie getCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        byte[] bytes = SerializationUtils.serialize(authorizationRequest);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    private OAuth2AuthorizationRequest deserialize(String cookieValue) {
        return deserializeStatic(cookieValue);
    }

    private static OAuth2AuthorizationRequest deserializeStatic(String cookieValue) {
        byte[] bytes = Base64.getUrlDecoder().decode(cookieValue);
        Object obj = SerializationUtils.deserialize(bytes);
        return (OAuth2AuthorizationRequest) obj;
    }

    /**
     * Save bind mode info to cookies (legacy, without bindKey)
     */
    public static void saveBindMode(HttpServletResponse response, String userId) {
        saveBindMode(response, userId, null);
    }

    /**
     * Save bind mode info to cookies with Redis bind key
     */
    public static void saveBindMode(HttpServletResponse response, String userId, String bindKey) {
        Cookie bindModeCookie = new Cookie(BIND_MODE_COOKIE, "true");
        bindModeCookie.setPath("/");
        bindModeCookie.setHttpOnly(true);
        bindModeCookie.setMaxAge(300); // 5 minutes
        response.addCookie(bindModeCookie);

        Cookie bindUserIdCookie = new Cookie(BIND_USER_ID_COOKIE, userId);
        bindUserIdCookie.setPath("/");
        bindUserIdCookie.setHttpOnly(true);
        bindUserIdCookie.setMaxAge(300);
        response.addCookie(bindUserIdCookie);

        if (bindKey != null && !bindKey.isBlank()) {
            Cookie bindKeyCookie = new Cookie(BIND_KEY_COOKIE, bindKey);
            bindKeyCookie.setPath("/");
            bindKeyCookie.setHttpOnly(true);
            bindKeyCookie.setMaxAge(300);
            response.addCookie(bindKeyCookie);
        }
    }

    /**
     * Read bind mode info from cookies
     */
    public static String getBindUserId(HttpServletRequest request) {
        Cookie bindModeCookie = getCookieStatic(request, BIND_MODE_COOKIE);
        if (bindModeCookie == null || !"true".equals(bindModeCookie.getValue())) {
            return null;
        }
        Cookie bindUserIdCookie = getCookieStatic(request, BIND_USER_ID_COOKIE);
        return bindUserIdCookie != null ? bindUserIdCookie.getValue() : null;
    }

    /**
     * Read bind key from cookies
     */
    public static String getBindKey(HttpServletRequest request) {
        Cookie bindKeyCookie = getCookieStatic(request, BIND_KEY_COOKIE);
        return bindKeyCookie != null ? bindKeyCookie.getValue() : null;
    }

    /**
     * Read bind key from serialized OAuth2 authorization request cookie.
     */
    public static String getBindKeyFromAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = getCookieStatic(request, COOKIE_NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        try {
            OAuth2AuthorizationRequest authorizationRequest = deserializeStatic(cookie.getValue());
            if (authorizationRequest == null || authorizationRequest.getAttributes() == null) {
                return null;
            }
            Object bindKey = authorizationRequest.getAttributes().get(BIND_KEY_ATTR);
            return bindKey != null ? String.valueOf(bindKey) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static BindContext consumeBindContext() {
        BindContext bindContext = BIND_CONTEXT_HOLDER.get();
        BIND_CONTEXT_HOLDER.remove();
        return bindContext;
    }

    public static void clearBindContext() {
        BIND_CONTEXT_HOLDER.remove();
    }

    /**
     * Fallback: read bind userId from serialized OAuth2 authorization request cookie.
     */
    public static String getBindUserIdFromAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = getCookieStatic(request, COOKIE_NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        try {
            OAuth2AuthorizationRequest authorizationRequest = deserializeStatic(cookie.getValue());
            if (authorizationRequest == null || authorizationRequest.getAttributes() == null) {
                return null;
            }
            Object bindUserId = authorizationRequest.getAttributes().get(BIND_USER_ID_ATTR);
            return bindUserId != null ? String.valueOf(bindUserId) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clear bind mode cookies
     */
    public static void clearBindMode(HttpServletResponse response) {
        Cookie bindModeCookie = new Cookie(BIND_MODE_COOKIE, "");
        bindModeCookie.setPath("/");
        bindModeCookie.setHttpOnly(true);
        bindModeCookie.setMaxAge(0);
        response.addCookie(bindModeCookie);

        Cookie bindUserIdCookie = new Cookie(BIND_USER_ID_COOKIE, "");
        bindUserIdCookie.setPath("/");
        bindUserIdCookie.setHttpOnly(true);
        bindUserIdCookie.setMaxAge(0);
        response.addCookie(bindUserIdCookie);

        Cookie bindKeyCookie = new Cookie(BIND_KEY_COOKIE, "");
        bindKeyCookie.setPath("/");
        bindKeyCookie.setHttpOnly(true);
        bindKeyCookie.setMaxAge(0);
        response.addCookie(bindKeyCookie);
    }

    private static Cookie getCookieStatic(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private static void cacheBindContext(HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
        String bindUserId = readBindAttrAsString(authorizationRequest, BIND_USER_ID_ATTR);
        String bindKey = readBindAttrAsString(authorizationRequest, BIND_KEY_ATTR);

        if (!StringUtils.hasText(bindUserId)) {
            bindUserId = getBindUserId(request);
        }
        if (!StringUtils.hasText(bindKey)) {
            bindKey = getBindKey(request);
        }

        if (StringUtils.hasText(bindUserId) || StringUtils.hasText(bindKey)) {
            BIND_CONTEXT_HOLDER.set(new BindContext(bindUserId, bindKey));
            log.info("[cacheBindContext] 缓存绑定上下文: bindUserId={}, bindKey={}", bindUserId, bindKey);
        } else {
            BIND_CONTEXT_HOLDER.remove();
        }
    }

    private static String readBindAttrAsString(OAuth2AuthorizationRequest authorizationRequest, String key) {
        if (authorizationRequest == null || authorizationRequest.getAttributes() == null) {
            return null;
        }
        Object value = authorizationRequest.getAttributes().get(key);
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value);
        return StringUtils.hasText(result) ? result : null;
    }
}
