package com.san.bastionpay.core.exception;

import com.san.bastionpay.auth.CurrentMemberAdvice;
import com.san.bastionpay.core.filter.MdcLoggingFilter;
import com.san.bastionpay.core.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러에서 난 예외를 에러 뷰로 바꾼다. 컨트롤러 밖(필터, sendError, 컨테이너) 오류는
 * Boot 기본 /error → CustomErrorAttributes → templates/error/* 가 맡는다.
 * 폼 검증 오류는 컨트롤러가 BindingResult 로 받아 같은 폼에 다시 보여 주므로 여기까지 오지 않는다.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusiness(BusinessException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        if (code.status().is5xxServerError()) {
            log.error("business_error code={} status={} detail={}", code.code(), code.status().value(), e.getMessage(), e);
        } else {
            log.warn("business_error code={} status={} detail={}", code.code(), code.status().value(), e.getMessage());
        }
        return render(request, code.status(), code);
    }

    /** 존재하지 않는 경로. 스캐너 탐지의 근거가 되므로 로그를 남긴다. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ModelAndView handleNotFound(Exception e, HttpServletRequest request) {
        log.warn("not_found method={} path={}", request.getMethod(), LogSanitizer.clean(request.getRequestURI()));
        return render(request, ErrorCode.NOT_FOUND.status(), ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ModelAndView handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("method_not_allowed method={} path={}", request.getMethod(), LogSanitizer.clean(request.getRequestURI()));
        return render(request, ErrorCode.METHOD_NOT_ALLOWED.status(), ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ModelAndView handleInvalidInput(Exception e, HttpServletRequest request) {
        log.warn("invalid_request method={} path={} exception={}",
                request.getMethod(), LogSanitizer.clean(request.getRequestURI()), e.getClass().getSimpleName());
        return render(request, ErrorCode.INVALID_INPUT.status(), ErrorCode.INVALID_INPUT);
    }

    /**
     * 그 밖의 모든 예외. 보안 예외는 반드시 다시 던져 Security(EntryPoint, AccessDeniedHandler)가 처리하게 한다.
     * 여기서 삼키면 401/403 이 500 으로 바뀐다.
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(Exception e, HttpServletRequest request) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        // 415, 406 등 Spring 이 상태를 정해 둔 웹 예외는 그 상태를 따른다
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            log.warn("invalid_request method={} path={} status={} exception={}", request.getMethod(),
                    LogSanitizer.clean(request.getRequestURI()), er.getStatusCode().value(), e.getClass().getSimpleName());
            return render(request, er.getStatusCode(), ErrorCode.INVALID_INPUT);
        }
        // 스택트레이스는 서버 로그에만 남기고 화면에는 참조 번호만 보여 준다
        log.error("unexpected_error method={} path={}", request.getMethod(), LogSanitizer.clean(request.getRequestURI()), e);
        return render(request, ErrorCode.INTERNAL_ERROR.status(), ErrorCode.INTERNAL_ERROR);
    }

    private static ModelAndView render(HttpServletRequest request, HttpStatusCode status, ErrorCode code) {
        ModelAndView mav = new ModelAndView(viewFor(status.value()));
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("errorCode", code.code());
        mav.addObject("errorMessage", code.message());
        mav.addObject("traceId", request.getAttribute(MdcLoggingFilter.TRACE_ID_ATTRIBUTE));
        // @ModelAttribute 는 예외 처리 뷰에는 적용되지 않으므로 헤더용 로그인 정보를 직접 넣는다
        mav.addObject("currentMember", CurrentMemberAdvice.resolve());
        return mav;
    }

    /** Boot 기본 /error 와 같은 규칙: 정확한 상태 템플릿 → 4xx / 5xx */
    static String viewFor(int status) {
        return switch (status) {
            case 401, 403, 404 -> "error/" + status;
            default -> status >= 500 ? "error/5xx" : "error/4xx";
        };
    }
}
