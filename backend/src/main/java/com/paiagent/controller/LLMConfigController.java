package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.Result;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "全局LLM配置接口")
@RestController
@RequestMapping("/api/llm-config")
public class LLMConfigController {

    @Autowired
    private LLMGlobalConfigService llmGlobalConfigService;

    @Operation(summary = "获取指定提供商的配置列表")
    @GetMapping("/{provider}")
    public Result<List<LLMGlobalConfig>> listByProvider(@PathVariable String provider, HttpServletRequest request) {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.listByProvider(provider);
        return Result.success(maskConfigsIfNeeded(configs, request));
    }

    @Operation(summary = "获取所有配置列表")
    @GetMapping
    public Result<List<LLMGlobalConfig>> listAll(HttpServletRequest request) {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.listAllForDisplay();
        return Result.success(maskConfigsIfNeeded(configs, request));
    }

    @Operation(summary = "获取配置详情")
    @GetMapping("/detail/{id}")
    public Result<LLMGlobalConfig> getById(@PathVariable Long id, HttpServletRequest request) {
        LLMGlobalConfig config = llmGlobalConfigService.getDecryptedById(id);
        if (config == null) {
            return Result.error("配置不存在");
        }
        return Result.success(maskConfigIfNeeded(config, request));
    }

    @Operation(summary = "获取指定提供商的默认配置")
    @GetMapping("/default/{provider}")
    public Result<LLMGlobalConfig> getDefaultConfig(@PathVariable String provider, HttpServletRequest request) {
        LLMGlobalConfig config = llmGlobalConfigService.getDefaultConfig(provider);
        return Result.success(maskConfigIfNeeded(config, request));
    }

    @Operation(summary = "保存配置（新增或更新）")
    @PostMapping
    public Result<LLMGlobalConfig> saveConfig(@RequestBody LLMGlobalConfig config, HttpServletRequest request) {
        if (!AuthContext.isAdmin(request)) {
            return Result.forbidden("只有管理员可以修改全局 LLM 配置");
        }

        try {
            LLMGlobalConfig saved = llmGlobalConfigService.saveConfig(config);
            return Result.success(saved);
        } catch (IllegalArgumentException e) {
            return Result.error("保存配置失败: " + e.getMessage());
        } catch (DuplicateKeyException e) {
            return Result.error("保存配置失败: 同一供应商下的配置别名不能重复");
        } catch (Exception e) {
            return Result.error("保存配置失败，请稍后重试");
        }
    }

    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthContext.isAdmin(request)) {
            return Result.forbidden("只有管理员可以删除全局 LLM 配置");
        }

        llmGlobalConfigService.deleteConfig(id);
        return Result.success();
    }

    @Operation(summary = "设置默认配置")
    @PostMapping("/{id}/default")
    public Result<Void> setDefaultConfig(@PathVariable Long id, HttpServletRequest request) {
        if (!AuthContext.isAdmin(request)) {
            return Result.forbidden("只有管理员可以设置默认全局 LLM 配置");
        }

        try {
            llmGlobalConfigService.setDefaultConfig(id);
            return Result.success();
        } catch (Exception e) {
            return Result.error("设置默认配置失败: " + e.getMessage());
        }
    }

    private List<LLMGlobalConfig> maskConfigsIfNeeded(List<LLMGlobalConfig> configs, HttpServletRequest request) {
        if (AuthContext.isAdmin(request)) {
            return configs;
        }
        return configs.stream()
                .map(this::maskConfig)
                .toList();
    }

    private LLMGlobalConfig maskConfigIfNeeded(LLMGlobalConfig config, HttpServletRequest request) {
        if (config == null || AuthContext.isAdmin(request)) {
            return config;
        }
        return maskConfig(config);
    }

    private LLMGlobalConfig maskConfig(LLMGlobalConfig config) {
        LLMGlobalConfig copy = new LLMGlobalConfig();
        BeanUtils.copyProperties(config, copy);
        copy.setApiKey("");
        return copy;
    }
}
