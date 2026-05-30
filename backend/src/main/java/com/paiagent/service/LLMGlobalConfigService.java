package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.engine.llm.LLMProviderRegistry;
import com.paiagent.mapper.LLMGlobalConfigMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class LLMGlobalConfigService extends ServiceImpl<LLMGlobalConfigMapper, LLMGlobalConfig> {

    private final ApiKeyCryptoService apiKeyCryptoService;

    public LLMGlobalConfigService(ApiKeyCryptoService apiKeyCryptoService) {
        this.apiKeyCryptoService = apiKeyCryptoService;
    }

    public List<LLMGlobalConfig> listByProvider(String provider) {
        return decryptConfigs(listRawByProvider(LLMProviderRegistry.normalizeProvider(provider)));
    }

    public List<LLMGlobalConfig> listAllForDisplay() {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(LLMGlobalConfig::getIsDefault)
                .orderByDesc(LLMGlobalConfig::getUpdatedAt);
        return decryptConfigs(this.list(wrapper));
    }

    public LLMGlobalConfig getDecryptedById(Long id) {
        return decryptConfig(super.getById(id));
    }

    public LLMGlobalConfig getDefaultConfig(String provider) {
        String normalizedProvider = LLMProviderRegistry.normalizeProvider(provider);
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, normalizedProvider)
                .eq(LLMGlobalConfig::getIsDefault, 1);
        return decryptConfig(this.getOne(wrapper));
    }

    @Transactional
    public void setDefaultConfig(Long id) {
        LLMGlobalConfig config = super.getById(id);
        if (config == null) {
            throw new RuntimeException("配置不存在");
        }

        LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                .set(LLMGlobalConfig::getIsDefault, 0);
        this.update(clearWrapper);

        LambdaUpdateWrapper<LLMGlobalConfig> setWrapper = new LambdaUpdateWrapper<>();
        setWrapper.eq(LLMGlobalConfig::getId, id)
                .set(LLMGlobalConfig::getIsDefault, 1);
        this.update(setWrapper);
    }

    @Transactional
    public LLMGlobalConfig saveConfig(LLMGlobalConfig config) {
        normalizeConfig(config);
        fillExistingApiKeyIfNecessary(config);
        validateConfig(config);
        purgeDeletedDuplicate(config);
        ensureUniqueProviderAndConfigName(config);

        long count = this.countByProvider(config.getProvider());
        LLMGlobalConfig storageConfig = copyConfig(config);
        storageConfig.setApiKey(apiKeyCryptoService.encrypt(config.getApiKey()));

        if (storageConfig.getId() == null) {
            if (count == 0) {
                storageConfig.setIsDefault(1);
            } else if (storageConfig.getIsDefault() == null) {
                storageConfig.setIsDefault(0);
            }
            this.save(storageConfig);
        } else {
            if (storageConfig.getIsDefault() != null && storageConfig.getIsDefault() == 1) {
                LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
                clearWrapper.eq(LLMGlobalConfig::getProvider, storageConfig.getProvider())
                        .ne(LLMGlobalConfig::getId, storageConfig.getId())
                        .set(LLMGlobalConfig::getIsDefault, 0);
                this.update(clearWrapper);
            }
            this.updateById(storageConfig);
        }

        return decryptConfig(storageConfig);
    }

    @Transactional
    public void deleteConfig(Long id) {
        LLMGlobalConfig config = super.getById(id);
        if (config == null) {
            return;
        }

        String provider = config.getProvider();
        boolean wasDefault = config.getIsDefault() == 1;

        baseMapper.hardDeleteById(id);

        if (wasDefault) {
            List<LLMGlobalConfig> remaining = listRawByProvider(provider);
            if (!remaining.isEmpty()) {
                LambdaUpdateWrapper<LLMGlobalConfig> setWrapper = new LambdaUpdateWrapper<>();
                setWrapper.eq(LLMGlobalConfig::getId, remaining.get(0).getId())
                        .set(LLMGlobalConfig::getIsDefault, 1);
                this.update(setWrapper);
            }
        }
    }

    private List<LLMGlobalConfig> listRawByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider)
                .orderByDesc(LLMGlobalConfig::getIsDefault)
                .orderByDesc(LLMGlobalConfig::getUpdatedAt);
        return this.list(wrapper);
    }

    private void purgeDeletedDuplicate(LLMGlobalConfig config) {
        LLMGlobalConfig existing = baseMapper.findAnyByProviderAndConfigName(
                config.getProvider(),
                config.getConfigName()
        );

        if (existing == null) {
            return;
        }

        boolean sameRecord = config.getId() != null && config.getId().equals(existing.getId());
        if (!sameRecord && existing.getDeleted() != null && existing.getDeleted() == 1) {
            baseMapper.hardDeleteById(existing.getId());
        }
    }

    private void fillExistingApiKeyIfNecessary(LLMGlobalConfig config) {
        if (config.getId() == null || config.getApiKey() != null) {
            return;
        }

        LLMGlobalConfig existing = super.getById(config.getId());
        if (existing != null) {
            config.setApiKey(apiKeyCryptoService.decrypt(existing.getApiKey()));
        }
    }

    private void normalizeConfig(LLMGlobalConfig config) {
        config.setProvider(LLMProviderRegistry.normalizeProvider(trimToNull(config.getProvider())));
        config.setConfigName(trimToNull(config.getConfigName()));
        config.setApiUrl(LLMProviderRegistry.resolveBaseUrl(config.getProvider(), config.getApiUrl()));
        config.setApiKey(trimToNull(config.getApiKey()));
        config.setModel(trimToNull(config.getModel()));
    }

    private void validateConfig(LLMGlobalConfig config) {
        if (config.getProvider() == null) {
            throw new IllegalArgumentException("供应商不能为空");
        }
        if (config.getConfigName() == null) {
            throw new IllegalArgumentException("配置别名不能为空");
        }
        if (config.getApiUrl() == null) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        validateApiUrl(config.getApiUrl());
        if (config.getApiKey() == null) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        if (config.getModel() == null) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
    }

    private void ensureUniqueProviderAndConfigName(LLMGlobalConfig config) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                .eq(LLMGlobalConfig::getConfigName, config.getConfigName());

        if (config.getId() != null) {
            wrapper.ne(LLMGlobalConfig::getId, config.getId());
        }

        if (this.count(wrapper) > 0) {
            throw new IllegalArgumentException("同一供应商下的配置别名不能重复");
        }
    }

    private long countByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider);
        return this.count(wrapper);
    }

    private List<LLMGlobalConfig> decryptConfigs(List<LLMGlobalConfig> configs) {
        return configs.stream()
                .map(this::decryptConfig)
                .toList();
    }

    private LLMGlobalConfig decryptConfig(LLMGlobalConfig config) {
        if (config == null) {
            return null;
        }

        LLMGlobalConfig copy = copyConfig(config);
        copy.setApiKey(apiKeyCryptoService.decrypt(copy.getApiKey()));
        return copy;
    }

    private LLMGlobalConfig copyConfig(LLMGlobalConfig config) {
        LLMGlobalConfig copy = new LLMGlobalConfig();
        BeanUtils.copyProperties(config, copy);
        return copy;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateApiUrl(String apiUrl) {
        String lowerUrl = apiUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains("/docs") || lowerUrl.contains("/ai-models") || lowerUrl.contains("www.kimi.com")) {
            throw new IllegalArgumentException("API 地址不能填写文档或网页地址，请填写接口根地址，例如 https://api.kimi.com/coding 或 https://api.moonshot.cn");
        }
    }
}
