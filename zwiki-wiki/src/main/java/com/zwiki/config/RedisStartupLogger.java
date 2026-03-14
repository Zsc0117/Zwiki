package com.zwiki.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RedisStartupLogger implements ApplicationRunner {

    private final Environment environment;

    public RedisStartupLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hostKey = "spring.data.redis.host";
        String portKey = "spring.data.redis.port";
        String dbKey = "spring.data.redis.database";

        String host = environment.getProperty(hostKey);
        String port = environment.getProperty(portKey);
        String database = environment.getProperty(dbKey);

        String hostSource = findFirstPropertySourceName(hostKey, host);
        String portSource = findFirstPropertySourceName(portKey, port);
        String dbSource = findFirstPropertySourceName(dbKey, database);

        String envHost = System.getenv("ZWIKI_REDIS_HOST");
        String envPort = System.getenv("ZWIKI_REDIS_PORT");
        String envDb = System.getenv("ZWIKI_REDIS_DATABASE");

        log.info("[redis] effective {}={} (source={})", hostKey, host, hostSource);
        log.info("[redis] effective {}={} (source={})", portKey, port, portSource);
        log.info("[redis] effective {}={} (source={})", dbKey, database, dbSource);
        log.info("[redis] env overrides present? ZWIKI_REDIS_HOST={}, ZWIKI_REDIS_PORT={}, ZWIKI_REDIS_DATABASE={} ",
                envHost != null, envPort != null, envDb != null);

        if (envHost != null || envPort != null || envDb != null) {
            log.info("[redis] env values: ZWIKI_REDIS_HOST={}, ZWIKI_REDIS_PORT={}, ZWIKI_REDIS_DATABASE={}", envHost, envPort, envDb);
        }

        log.info("[redis] sources containing {}: {}", hostKey, listPropertySourceNamesContaining(hostKey));
        log.info("[redis] sources containing {}: {}", portKey, listPropertySourceNamesContaining(portKey));
        log.info("[redis] sources containing {}: {}", dbKey, listPropertySourceNamesContaining(dbKey));
    }

    private String findFirstPropertySourceName(String key, String effectiveValue) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return "unknown";
        }

        for (PropertySource<?> ps : configurableEnvironment.getPropertySources()) {
            if ("configurationProperties".equals(ps.getName())) {
                continue;
            }
            try {
                Object value = ps.getProperty(key);
                if (value != null) {
                    if (effectiveValue == null || effectiveValue.equals(String.valueOf(value))) {
                        return ps.getName();
                    }
                    return ps.getName();
                }
            } catch (Exception ignored) {
            }
        }
        return "not_found";
    }

    private List<String> listPropertySourceNamesContaining(String key) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (PropertySource<?> ps : configurableEnvironment.getPropertySources()) {
            if ("configurationProperties".equals(ps.getName())) {
                continue;
            }
            try {
                Object value = ps.getProperty(key);
                if (value != null) {
                    names.add(ps.getName());
                }
            } catch (Exception ignored) {
            }
        }
        return names;
    }
}
