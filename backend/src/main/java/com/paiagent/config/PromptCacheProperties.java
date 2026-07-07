package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.prompt-cache")
public class PromptCacheProperties {

    private boolean enabled = true;

    private int ttlSeconds = 3600;

    private int maxEntries = 2048;

    private int minimumChars = 256;

    public static PromptCacheProperties disabled() {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setEnabled(false);
        return properties;
    }
}
