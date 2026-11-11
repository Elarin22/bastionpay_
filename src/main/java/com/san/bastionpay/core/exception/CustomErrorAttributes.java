package com.san.bastionpay.core.exception;

import com.san.bastionpay.core.filter.MdcLoggingFilter;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * 컨트롤러 밖(필터, sendError, 컨테이너)에서 난 오류에도 에러 페이지가 참조 번호를 보여 줄 수 있게 한다.
 * 에러 디스패치 시점엔 MDC가 비워져 있을 수 있어 request attribute에서 읽는다.
 */
@Component
public class CustomErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
        Object traceId = webRequest.getAttribute(MdcLoggingFilter.TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (traceId != null) {
            attributes.put("traceId", traceId);
        }
        return attributes;
    }
}
