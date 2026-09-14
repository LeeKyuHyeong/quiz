package com.kh.game.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("없는 경로 페이지 요청은 500이 아니라 404 에러 페이지")
    void notFound_pageRequest_returns404ErrorPage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/robots.txt");
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/robots.txt");

        Object result = handler.handleNotFound(ex, request);

        assertThat(result).isInstanceOf(ModelAndView.class);
        ModelAndView mav = (ModelAndView) result;
        assertThat(mav.getViewName()).isEqualTo("error");
        assertThat(mav.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("없는 경로 API 요청은 404 JSON")
    void notFound_apiRequest_returns404Json() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.git/HEAD");
        request.addHeader("Accept", "application/json");
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/.git/HEAD");

        Object result = handler.handleNotFound(ex, request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("그 외 예외는 여전히 500")
    void otherException_returns500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader("Accept", "application/json");

        Object result = handler.handleException(new RuntimeException("boom"), request);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
