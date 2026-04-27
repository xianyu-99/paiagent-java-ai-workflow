package com.paiagent.dto;

import lombok.Data;

/**
 * Refresh Token 请求 DTO
 */
@Data
public class RefreshTokenRequest {

    private String refreshToken;
}
