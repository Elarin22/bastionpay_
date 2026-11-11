package com.san.bastionpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BastionpayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BastionpayApplication.class, args);
    }

}
