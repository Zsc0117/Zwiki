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
 * @author pai
 * @description: GitHub API服务
 * @date 2026/1/30
 */
@Slf4j
@Service
public class GithubApiService {

    private final RestClient restClient;

    public GithubApiService(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .build();
    }

    public List<GithubRepoDto> listPrivateRepos(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return List.of();
        }

        String uri = "/user/repos?visibility=private&affiliation=owner,collaborator,organization_member&sort=updated&direction=desc&per_page=100";

        try {
            JsonNode body = restClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
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
                dto.setCloneUrl(n.path("clone_url").asText(null));
                dto.setUpdatedAt(n.path("updated_at").asText(null));
                result.add(dto);
            }
            return result;
        } catch (RestClientResponseException e) {
            String msg = "GitHub私有仓库获取失败";
            int status = e.getRawStatusCode();
            if (status == 401) {
                msg = "GitHub授权已失效，请退出后重新使用GitHub登录授权";
            } else if (status == 403) {
                msg = "GitHub权限不足(需要repo权限)，请退出后重新使用GitHub登录授权";
            }
            log.error("GitHub私有仓库列表获取失败: status={}, body={}, message={}", status, e.getResponseBodyAsString(), e.getMessage(), e);
            throw new IllegalStateException(msg);
        } catch (Exception e) {
            log.error("GitHub私有仓库列表获取失败: {}", e.getMessage(), e);
            throw new IllegalStateException("GitHub私有仓库获取失败，请稍后重试");
        }
    }
 }
