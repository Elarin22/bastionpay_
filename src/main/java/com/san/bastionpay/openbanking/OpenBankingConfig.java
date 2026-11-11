package com.san.bastionpay.openbanking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class OpenBankingConfig {

    /** 응답이 늦어도 요청 스레드가 오래 묶이지 않도록 시간 제한을 둔다. 리다이렉트는 따라가지 않는다. */
    @Bean
    RestClient openBankingRestClient(OpenBankingProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }
}
