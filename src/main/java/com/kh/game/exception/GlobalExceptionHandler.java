package com.kh.game.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Object handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("BusinessException: {}", ex.getMessage());

        if (isApiRequest(request)) {
            return ResponseEntity.status(ex.getStatus()).body(errorMap(ex.getMessage()));
        }
        return errorPage(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMap(ex.getMessage()));
        }
        return errorPage(ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Object handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("IllegalStateException: {}", ex.getMessage());

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorMap(ex.getMessage()));
        }
        return errorPage(ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("AccessDeniedException: uri={}", request.getRequestURI());

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorMap("접근 권한이 없습니다."));
        }
        return new ModelAndView("redirect:/auth/login");
    }

    // 매핑되지 않은 경로(/robots.txt, /.git/HEAD 같은 스캐너 요청 포함). 500 이 아니라 404 로, 스택트레이스 없이.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public Object handleNotFound(Exception ex, HttpServletRequest request) {
        log.debug("Not found: uri={}", request.getRequestURI());

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorMap("페이지를 찾을 수 없습니다."));
        }
        ModelAndView mav = errorPage("페이지를 찾을 수 없습니다.");
        mav.setStatus(HttpStatus.NOT_FOUND);
        return mav;
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: uri={}", request.getRequestURI(), ex);

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorMap("서버 오류가 발생했습니다."));
        }
        return errorPage("서버 오류가 발생했습니다.");
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String xRequested = request.getHeader("X-Requested-With");
        return "XMLHttpRequest".equals(xRequested)
                || (accept != null && accept.contains("application/json"));
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("success", false, "message", message);
    }

    private ModelAndView errorPage(String message) {
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorMessage", message);
        return mav;
    }
}
