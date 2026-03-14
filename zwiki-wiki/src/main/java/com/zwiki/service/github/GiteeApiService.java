package com.zwiki.service.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwiki.domain.dto.GithubRepoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Gitee API服务
 */
@Slf4j
@Service
public class GiteeApiService {

    private final RestClient restClient;

    public GiteeApiService(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://gitee.com/api/v5")
                .build();
    }

    /**
     * 获取用户的所有仓库（包括私有仓库）
     * Gitee API: GET /user/repos
     * https://gitee.com/api/v5/swagger#/getV5UserRepos
     */
    public List<GithubRepoDto> listUserRepos(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return List.of();
        }

        // Gitee API: type=all includes private repos, sort by updated
        String uri = "/user/repos?access_token=" + accessToken + "&type=all&sort=updated&direction=desc&per_page=100";

        try {
            JsonNode body = restClient.get()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.isArray()) {
                return List.of();
            }

            List<GithubRepoDto> result = new ArrayList<>();
            for (JsonNode n : body) {
                GithubRepoDto dto = new GithubRepoDto();
                dto.setId(n.path("id").asText(null));
                dto.setName(n.path("name").asText(null));
                dto.setFullName(n.path("full_name").asText(null));
                // Gitee uses "owner.login" same as GitHub
                dto.setOwnerLogin(n.path("owner").path("login").asText(null));
                // Gitee uses "private" field same as GitHub
                dto.setPrivate(n.path("private").asBoolean(false));
                dto.setDescription(n.path("description").asText(null));
                dto.setLanguage(n.path("language").asText(null));
                // Gitee uses "stargazers_count" same as GitHub
                dto.setStargazersCount(n.path("stargazers_count").isNumber() ? n.path("stargazers_count").asInt() : null);
                // Gitee clone URL field
                dto.setCloneUrl(n.path("html_url").asText(null) + ".git");
                dto.setUpdatedAt(n.path("updated_at").asText(null));
                result.add(dto);
            }
            return result;
        } catch (RestClientResponseException e) {
            String msg = "Gitee仓库获取失败";
            int status = e.getStatusCode().value();
            if (status == 401) {
                msg = "Gitee授权已失效，请退出后重新使用Gitee登录授权";
            } else if (status == 403) {
                msg = "Gitee权限不足，请退出后重新使用Gitee登录授权";
            }
            log.error("Gitee仓库列表获取失败: status={}, body={}, message={}", status, e.getResponseBodyAsString(), e.getMessage(), e);
            throw new IllegalStateException(msg);
        } catch (Exception e) {
            log.error("Gitee仓库列表获取失败: {}", e.getMessage(), e);
            throw new IllegalStateException("Gitee仓库获取失败，请稍后重试");
        }
    }

    /**
     * 获取用户的私有仓库
     */
    public List<GithubRepoDto> listPrivateRepos(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return List.of();
        }

        // Gitee API: type=private for private repos only
        String uri = "/user/repos?access_token=" + accessToken + "&type=private&sort=updated&direction=desc&per_page=100";

        try {
            JsonNode body = restClient.get()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !body.isArray()) {
                return List.of();
            }

            List<GithubRepoDto> result = new ArrayList<>();
            for (JsonNode n : body) {
                GithubRepoDto dto = new GithubRepoDto();
                dto.setId(n.path("id").asText(null));
                dto.setName(n.path("name").asText(null));
                dto.setFullName(n.path("full_name").asText(null));
                dto.setOwnerLogin(n.path("owner").path("login").asText(null));
                dto.setPrivate(n.path("private").asBoolean(false));
                dto.setDescription(n.path("description").asText(null));
                dto.setLanguage(n.path("language").asText(null));
                dto.setStargazersCount(n.path("stargazers_count").isNumber() ? n.path("stargazers_count").asInt() : null);
                dto.setCloneUrl(n.path("html_url").asText(null) + ".git");
                dto.setUpdatedAt(n.path("updated_at").asText(null));
                result.add(dto);
            }
            return result;
        } catch (RestClientResponseException e) {
            String msg = "Gitee私有仓库获取失败";
            int status = e.getStatusCode().value();
            if (status == 401) {
                msg = "Gitee授权已失效，请退出后重新使用Gitee登录授权";
            } else if (status == 403) {
                msg = "Gitee权限不足(需要projects权限)，请退出后重新使用Gitee登录授权";
            }
            log.error("Gitee私有仓库列表获取失败: status={}, body={}, message={}", status, e.getResponseBodyAsString(), e.getMessage(), e);
            throw new IllegalStateException(msg);
        } catch (Exception e) {
            log.error("Gitee私有仓库列表获取失败: {}", e.getMessage(), e);
            throw new IllegalStateException("Gitee私有仓库获取失败，请稍后重试");
        }
    }
}
