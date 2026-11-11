package com.san.mockbank.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class TokenController {

    private final OauthService oauth;

    public TokenController(OauthService oauth) {
        this.oauth = oauth;
    }

    /** 인증 코드(authorization_code) 또는 이용기관 인증(client_credentials)으로 토큰을 발급한다. */
    @PostMapping(value = "/oauth/2.0/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<TokenResponse> token(@RequestParam(name = "grant_type", required = false) String grantType,
                                               @RequestParam(name = "client_id", required = false) String clientId,
                                               @RequestParam(name = "client_secret", required = false) String clientSecret,
                                               @RequestParam(required = false) String code,
                                               @RequestParam(name = "redirect_uri", required = false) String redirectUri,
                                               @RequestParam(required = false) String scope) {
        OauthService.IssuedToken issued = switch (grantType == null ? "" : grantType) {
            case "authorization_code" -> oauth.exchangeCode(clientId, clientSecret, code, redirectUri);
            case "client_credentials" -> oauth.clientCredentials(clientId, clientSecret, scope);
            default -> throw new OauthException("unsupported_grant_type", HttpStatus.BAD_REQUEST, "grant_type");
        };
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("Pragma", "no-cache")
                .body(new TokenResponse(issued.accessToken(), "Bearer", issued.expiresIn(), issued.scope(), issued.userSeqNo()));
    }

    @ExceptionHandler(OauthException.class)
    ResponseEntity<Map<String, String>> oauthError(OauthException e) {
        // 내부 사유(detail)는 응답에 싣지 않는다
        return ResponseEntity.status(e.getStatus()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("error", e.getError()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("scope") String scope,
            @JsonProperty("user_seq_no") String userSeqNo) {
        @Override
        public String toString() {
            return "TokenResponse[scope=" + scope + "]";
        }
    }
}
