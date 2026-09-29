package com.eyemonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EyeMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EyeMonitorApplication.class, args);
    }
}