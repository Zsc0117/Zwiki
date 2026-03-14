package com.zwiki.service;
import com.zwiki.repository.dao.ZwikiUserRepository;
import com.zwiki.repository.entity.ZwikiUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按场景解析用户指定的LLM模型。
 * 优先级: 场景模型 → defaultModel → null(走负载均衡)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneModelResolver {

    private final ZwikiUserRepository zwikiUserRepository;

    public enum Scene {
        CATALOGUE,
        DOC_GEN,
        CHAT,
        ASSISTANT
    }

    /**
     * 解析指定场景应使用的模型名称。
     *
     * @param userId 用户ID
     * @param scene  场景枚举
     * @return 模型名称，null 表示走负载均衡
     */
    public String resolve(String userId, Scene scene) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        ZwikiUser user = zwikiUserRepository.findFirstByUserId(userId).orElse(null);
        if (user == null) {
            return null;
        }

        String sceneModel = switch (scene) {
            case CATALOGUE -> user.getCatalogueModel();
            case DOC_GEN -> user.getDocGenModel();
            case CHAT -> user.getChatModel();
            case ASSISTANT -> user.getAssistantModel();
        };

        if (StringUtils.hasText(sceneModel)) {
            log.debug("Scene {} resolved to explicit model '{}' for user {}", scene, sceneModel, userId);
            return sceneModel;
        }

        return null;
    }
}
