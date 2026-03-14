package com.zwiki.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zwiki.service.auth.OAuthUserService;
import com.zwiki.service.auth.HttpCookieOAuth2AuthorizationRequestRepository;
import com.zwiki.common.result.ResultVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    private final OAuthUserService oAuthUserService;
    private final ObjectMapper objectMapper;

    @Value("${zwiki.auth.login-success-redirect:/}")
    private String loginSuccessRedirect;

    @Value("${zwiki.auth.bind-success-redirect:http://localhost:3000/center/settings}")
    private String bindSuccessRedirect;

    @Value("${zwiki.auth.login-failure-redirect:http://localhost:3000/login?error}")
    private String loginFailureRedirect;

    @Value("${git.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${git.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${git.proxy.port:7890}")
    private int proxyPort;

    @Bean
    public HttpCookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository() {
        return new HttpCookieOAuth2AuthorizationRequestRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
                                                   @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource,
                                                   OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient) throws Exception {

        ClientRegistrationRepository clientRegistrationRepository = clientRegistrationRepositoryProvider.getIfAvailable();

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource));

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    if (isApiRequest(request)) {
                        writeJson(response, 401, ResultVo.error(401, "未登录"));
                        return;
                    }
                    new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)
                            .commence(request, response, authException);
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    if (isApiRequest(request)) {
                        writeJson(response, 403, ResultVo.error(403, "无权限"));
                        return;
                    }
                    response.setStatus(403);
                })
        );

        if (clientRegistrationRepository != null) {
            logAvailableRegistrations(clientRegistrationRepository);

            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                            .requestMatchers("/api/auth/me").permitAll()
                            .requestMatchers("/api/auth/logout", "/api/auth/github/**", "/api/auth/gitee/**").authenticated()
                            .requestMatchers("/api/auth/bindstart/**", "/api/auth/linked-accounts", "/api/auth/unbind/**").authenticated()
                            .requestMatchers("/api/auth/apikey/**").authenticated()
                            .requestMatchers("/api/llm/**", "/api/mcp/**").authenticated()
                            .requestMatchers("/api/chat/**").authenticated()
                            .requestMatchers("/api/diagram/**").authenticated()
                            .requestMatchers("/api/auth/settings").authenticated()
                            .requestMatchers("/api/auth/wiki-webhook/**").authenticated()
                            .requestMatchers("/api/webhook/wiki/**").permitAll()
                            .requestMatchers("/api/task/create/**", "/api/task/delete", "/api/task/update").authenticated()
                            .anyRequest().permitAll()
                    )
                    .oauth2Login(oauth2 -> oauth2
                            .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                                    .authorizationRequestRepository(cookieOAuth2AuthorizationRequestRepository())
                            )
                            .tokenEndpoint(tokenEndpoint -> tokenEndpoint
                                    .accessTokenResponseClient(accessTokenResponseClient)
                            )
                            .userInfoEndpoint(userInfo -> userInfo.userService(oAuthUserService))
                            .successHandler((request, response, authentication) -> {
                                boolean wasBind = false;
                                Object principal = authentication.getPrincipal();
                                logger.info("[OAuth2 successHandler] principal类型: {}", principal != null ? principal.getClass().getSimpleName() : "null");
                                if (principal instanceof OAuth2User oAuth2User) {
                                    Object bindModeObj = oAuth2User.getAttributes().get("zwikiBindMode");
                                    logger.info("[OAuth2 successHandler] zwikiBindMode={}, zwikiUserId={}, zwikiProvider={}", 
                                            bindModeObj, 
                                            oAuth2User.getAttributes().get("zwikiUserId"),
                                            oAuth2User.getAttributes().get("zwikiProvider"));
                                    wasBind = Boolean.TRUE.equals(bindModeObj)
                                            || "true".equalsIgnoreCase(String.valueOf(bindModeObj));

                                    Object userIdObj = oAuth2User.getAttributes().get("zwikiUserId");
                                    if (userIdObj != null) {
                                        String userId = String.valueOf(userIdObj);

                                        if (wasBind) {
                                            // Bind flow: don't create new session, keep existing one
                                            logger.info("绑定流程完成，保持当前会话和登录方式: userId={}", userId);
                                        } else {
                                            // Login flow: create new Sa-Token session
                                            String token = StpUtil.createLoginSession(userId);

                                            // 手动设置Cookie
                                            Cookie cookie = new Cookie("satoken", token);
                                            cookie.setPath("/");
                                            cookie.setHttpOnly(true);
                                            cookie.setMaxAge(86400); // 1天
                                            response.addCookie(cookie);

                                            logger.info("Sa-Token登录成功: userId={}, token={}", userId, token.substring(0, 8) + "...");

                                            // Only set login-provider marker on real login, not on bind.
                                            Object providerObj = oAuth2User.getAttributes().get("zwikiProvider");
                                            if (providerObj != null) {
                                                Cookie providerCookie = new Cookie("ZWIKI_LOGIN_PROVIDER", String.valueOf(providerObj));
                                                providerCookie.setPath("/");
                                                providerCookie.setHttpOnly(true);
                                                providerCookie.setMaxAge(86400);
                                                response.addCookie(providerCookie);
                                                logger.info("更新登录提供方Cookie: userId={}, provider={}, bindMode={}", userId, providerObj, wasBind);
                                            }
                                        }
                                    }
                                }

                                HttpCookieOAuth2AuthorizationRequestRepository.clearBindMode(response);

                                response.sendRedirect(wasBind ? bindSuccessRedirect : loginSuccessRedirect);
                            })
                            .failureHandler((request, response, exception) -> {
                                HttpCookieOAuth2AuthorizationRequestRepository.clearBindMode(response);
                                logger.warn("OAuth2 login failed: {}", exception != null ? exception.getMessage() : "unknown");
                                response.sendRedirect(loginFailureRedirect);
                            })
                    );
        } else {
            logger.warn("ClientRegistrationRepository not found; OAuth2 login is disabled. Ensure Nacos DataId zwiki-wiki-service-dev.yaml provides spring.security.oauth2.client.registration.github.*");
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                    .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/github/**").permitAll()
                    .anyRequest().permitAll()
            );
        }

        http.logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    // 使用Sa-Token登出
                    try {
                        if (StpUtil.isLogin()) {
                            String token = StpUtil.getTokenValue();
                            StpUtil.logout();
                            logger.info("Sa-Token登出成功: token={}", token != null && token.length() > 8 ? token.substring(0, 8) + "..." : token);
                        }
                    } catch (Exception e) {
                        logger.warn("Sa-Token登出异常: {}", e.getMessage());
                    }
                    
                    // 清除Cookie
                    Cookie cookie = new Cookie("satoken", "");
                    cookie.setPath("/");
                    cookie.setHttpOnly(true);
                    cookie.setMaxAge(0);
                    response.addCookie(cookie);

                    Cookie providerCookie = new Cookie("ZWIKI_LOGIN_PROVIDER", "");
                    providerCookie.setPath("/");
                    providerCookie.setHttpOnly(true);
                    providerCookie.setMaxAge(0);
                    response.addCookie(providerCookie);
                    
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(200);
                    response.getWriter().write(objectMapper.writeValueAsString(ResultVo.success()));
                })
        );

        return http.build();
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : null;
        if (uri != null && uri.startsWith("/api/")) {
            return true;
        }
        String accept = request != null ? request.getHeader("Accept") : null;
        if (accept != null && accept.contains("application/json")) {
            return true;
        }
        String xrw = request != null ? request.getHeader("X-Requested-With") : null;
        return xrw != null && xrw.contains("XMLHttpRequest");
    }

    private void writeJson(HttpServletResponse response, int httpStatus, Object body) throws java.io.IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(httpStatus);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();
        RestTemplate restTemplate = new RestTemplate(List.of(
                new FormHttpMessageConverter(),
                new OAuth2AccessTokenResponseHttpMessageConverter()
        ));

        if (proxyEnabled && StringUtils.hasText(proxyHost) && proxyPort > 0) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
            restTemplate.setRequestFactory(requestFactory);
        }

        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        client.setRestOperations(restTemplate);
        return client;
    }

    private void logAvailableRegistrations(ClientRegistrationRepository repository) {
        if (repository instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder();
            for (Object obj : iterable) {
                if (obj instanceof ClientRegistration registration) {
                    if (!sb.isEmpty()) {
                        sb.append(",");
                    }
                    sb.append(registration.getRegistrationId());
                }
            }
            if (!sb.isEmpty()) {
                logger.info("OAuth2 client registrations loaded: {}", sb);
            } else {
                logger.warn("ClientRegistrationRepository is present but contains no ClientRegistration items.");
            }
        } else {
            logger.info("ClientRegistrationRepository bean detected: {}", repository.getClass().getName());
        }
    }
}
