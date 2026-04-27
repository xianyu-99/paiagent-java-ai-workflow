package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.LoginRequest;
import com.paiagent.dto.LoginResponse;
import com.paiagent.dto.RefreshTokenRequest;
import com.paiagent.dto.RegisterRequest;
import com.paiagent.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证接口")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthTokens tokens = authService.login(request.getUsername(), request.getPassword());
        if (tokens == null) {
            return Result.error("用户名或密码错误");
        }

        return Result.success(toLoginResponse(tokens));
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        try {
            AuthService.AuthTokens tokens = authService.register(request.getUsername(), request.getPassword());
            return Result.success(toLoginResponse(tokens));
        } catch (DuplicateKeyException e) {
            return Result.error("用户名已存在");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(request != null ? request.getRefreshToken() : null);
        return Result.success();
    }

    @Operation(summary = "刷新访问令牌")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody RefreshTokenRequest request) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            return Result.unauthorized("Refresh Token 不能为空");
        }

        AuthService.AuthTokens tokens = authService.refresh(request.getRefreshToken());
        if (tokens == null) {
            return Result.unauthorized("Refresh Token 无效或已过期");
        }

        return Result.success(toLoginResponse(tokens));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/current")
    public Result<LoginResponse.UserInfo> getCurrentUser(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            AuthService.UserInfo userInfo = authService.getUserInfoByToken(token);
            if (userInfo != null) {
                return Result.success(new LoginResponse.UserInfo(userInfo.id(), userInfo.username(), userInfo.role()));
            }
        }
        return Result.unauthorized("未认证");
    }

    private LoginResponse toLoginResponse(AuthService.AuthTokens tokens) {
        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(tokens.userId(), tokens.username(), tokens.role());
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), userInfo);
    }
}
