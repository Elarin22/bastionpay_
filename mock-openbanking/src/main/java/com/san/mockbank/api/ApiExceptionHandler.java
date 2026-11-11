package com.san.mockbank.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice(basePackages = "com.san.mockbank.api")
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handle(ApiException e) {
        log.info("api error {} {}", e.getRspCode().code(), e.getMessage());
        return body(e.getRspCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, String>> missing(MissingServletRequestParameterException e) {
        return body(RspCode.INVALID_PARAMETER);
    }

    /** 같은 거래고유번호가 동시에 들어와 DB 유일 제약이 막은 경우. 트랜잭션은 이미 롤백되었다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        log.warn("api 데이터 무결성 오류(동시 중복 요청으로 추정): {}", e.getClass().getSimpleName());
        return body(RspCode.DUPLICATE_TRAN_ID);
    }

    /** 본문이 JSON 이 아니거나 형식이 맞지 않는 경우. 파서의 내부 메시지는 응답에 싣지 않는다. */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, String>> unreadable(Exception e) {
        return body(RspCode.INVALID_PARAMETER);
    }

    private static ResponseEntity<Map<String, String>> body(RspCode code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("rsp_code", code.code());
        body.put("rsp_message", code.message());
        return ResponseEntity.status(code.status()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
