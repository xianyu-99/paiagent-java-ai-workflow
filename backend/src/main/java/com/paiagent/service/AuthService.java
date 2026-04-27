package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.UserRole;
import com.paiagent.config.JwtSecretProvider;
import com.paiagent.entity.AppUser;
import com.paiagent.mapper.AppUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

    @Value("${paiagent.default-username:}")
    private String defaultUsername;

    @Value("${paiagent.default-password:}")
    private String defaultPassword;

    @Autowired
    private JwtSecretProvider jwtSecretProvider;

    @Value("${paiagent.auth.access-token-expiration-minutes:120}")
    private long accessTokenExpirationMinutes;

    @Value("${paiagent.auth.refresh-token-expiration-hours:168}")
    private long refreshTokenExpirationHours;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    public AuthTokens login(String username, String password) {
        AppUser user = findActiveUserByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Login failed - username or password mismatch: {}", username);
            return null;
        }

        log.info("Login successful for user: {}", username);
        return issueTokens(user);
    }

    public AuthTokens register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername == null) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new IllegalArgumentException("密码长度必须在 6 到 72 个字符之间");
        }
        if (findAnyUserByUsername(normalizedUsername) != null) {
            throw new DuplicateKeyException("用户名已存在");
        }

        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(UserRole.USER.name());
        user.setEnabled(1);
        appUserMapper.insert(user);

        return issueTokens(user);
    }

    public AuthTokens refresh(String refreshToken) {
        Long userId = getUserIdByRefreshToken(refreshToken);
        if (userId == null) {
            return null;
        }

        AppUser user = appUserMapper.selectById(userId);
        if (!isActive(user)) {
            revokeRefreshToken(refreshToken);
            return null;
        }

        revokeRefreshToken(refreshToken);
        return issueTokens(user);
    }

    public void logout(String refreshToken) {
        revokeRefreshToken(refreshToken);
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return ACCESS_TOKEN_TYPE.equals(claims.get("tokenType"))
                    && claims.getExpiration() != null
                    && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsernameByToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return ACCESS_TOKEN_TYPE.equals(claims.get("tokenType")) ? claims.getSubject() : null;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Long getUserIdByToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!ACCESS_TOKEN_TYPE.equals(claims.get("tokenType"))) {
                return null;
            }
            Object userId = claims.get("userId");
            if (userId instanceof Number value) {
                return value.longValue();
            }
            return userId == null ? null : Long.parseLong(String.valueOf(userId));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public String getRoleByToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!ACCESS_TOKEN_TYPE.equals(claims.get("tokenType"))) {
                return null;
            }
            return UserRole.normalize(String.valueOf(claims.get("role")));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public UserInfo getUserInfoByToken(String token) {
        Long userId = getUserIdByToken(token);
        String username = getUsernameByToken(token);
        String role = getRoleByToken(token);
        if (userId == null || username == null || role == null) {
            return null;
        }
        return new UserInfo(userId, username, role);
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(buildRefreshTokenKey(refreshToken));
    }

    public AppUser ensureDefaultAdmin() {
        String username = normalizeUsername(defaultUsername);
        if (username == null || defaultPassword == null || defaultPassword.isBlank()) {
            log.info("Default admin bootstrap skipped because default credentials are empty");
            return null;
        }

        AppUser user = findAnyUserByUsername(username);
        if (user != null) {
            if (!UserRole.isAdmin(user.getRole()) || user.getEnabled() == null || user.getEnabled() != 1) {
                user.setRole(UserRole.ADMIN.name());
                user.setEnabled(1);
                appUserMapper.updateById(user);
            }
            return user;
        }

        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(defaultPassword));
        admin.setRole(UserRole.ADMIN.name());
        admin.setEnabled(1);
        appUserMapper.insert(admin);
        log.info("Default admin user created: {}", username);
        return admin;
    }

    private AuthTokens issueTokens(AppUser user) {
        String role = UserRole.normalize(user.getRole());
        String accessToken = createAccessToken(user.getId(), user.getUsername(), role);
        String refreshToken = createRefreshToken();

        stringRedisTemplate.opsForValue().set(
                buildRefreshTokenKey(refreshToken),
                String.valueOf(user.getId()),
                Duration.ofHours(refreshTokenExpirationHours)
        );

        return new AuthTokens(accessToken, refreshToken, user.getId(), user.getUsername(), role);
    }

    private String createAccessToken(Long userId, String username, String role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenExpirationMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("tokenType", ACCESS_TOKEN_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();
    }

    private String createRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Long getUserIdByRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(buildRefreshTokenKey(refreshToken));
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    private String buildRefreshTokenKey(String refreshToken) {
        return REFRESH_TOKEN_PREFIX + refreshToken;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecretProvider.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private AppUser findActiveUserByUsername(String username) {
        AppUser user = findAnyUserByUsername(username);
        return isActive(user) ? user : null;
    }

    private AppUser findAnyUserByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername == null) {
            return null;
        }

        LambdaQueryWrapper<AppUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AppUser::getUsername, normalizedUsername);
        return appUserMapper.selectOne(wrapper);
    }

    private boolean isActive(AppUser user) {
        return user != null
                && user.getEnabled() != null
                && user.getEnabled() == 1
                && (user.getDeleted() == null || user.getDeleted() == 0);
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AuthTokens(String accessToken, String refreshToken, Long userId, String username, String role) {
    }

    public record UserInfo(Long id, String username, String role) {
    }
}
