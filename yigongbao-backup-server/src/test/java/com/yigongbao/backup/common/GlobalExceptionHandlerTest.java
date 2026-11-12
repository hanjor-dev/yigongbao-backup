package com.yigongbao.backup.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/jobs");

    @Test
    void notLoggedInReturnsUnauthorizedEnvelope() {
        var response = handler.notLoggedIn(
                NotLoginException.newInstance("default-login", NotLoginException.TOKEN_TIMEOUT,
                        "token 已过期", null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void missingRoleReturnsForbiddenEnvelope() {
        var response = handler.forbidden(new NotRoleException("ADMIN", "default-login"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }
}
