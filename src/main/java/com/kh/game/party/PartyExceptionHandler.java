package com.kh.game.party;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * 파티 경로의 오류 응답. 값이 틀리면 400, 지금 단계에서 할 수 없으면 409.
 * 응답 형태는 기존 관리자 API 와 같은 {success:false, message}.
 */
@RestControllerAdvice(basePackages = "com.kh.game.party")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PartyExceptionHandler {

    /** stale 은 화면이 "상태를 다시 받아라" 와 "조작이 거부됐다" 를 구분하는 표시. */
    @ExceptionHandler(PartyStaleException.class)
    public ResponseEntity<Map<String, Object>> handleStale(PartyStaleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("success", false, "stale", true, "message", ex.getMessage()));
    }

    @ExceptionHandler(PartyInputException.class)
    public ResponseEntity<Map<String, Object>> handleInput(PartyInputException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(PartyGameException.class)
    public ResponseEntity<Map<String, Object>> handleGame(PartyGameException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "빠진 값이 있습니다: " + ex.getParameterName());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex) {
        return error(HttpStatus.BAD_REQUEST, "빠진 값이 있습니다: " + ex.getRequestPartName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "값을 알 수 없습니다: " + ex.getName());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "message", message));
    }
}
