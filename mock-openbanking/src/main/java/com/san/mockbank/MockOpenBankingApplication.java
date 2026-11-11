package com.san.mockbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 금융결제원 오픈뱅킹을 흉내 내는 교육용 가짜 서버. 실제 금융 거래는 일어나지 않는다. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockOpenBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockOpenBankingApplication.class, args);
    }
}
