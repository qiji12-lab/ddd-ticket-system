package com.ticketide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TickeTideApplication {

    public static void main(String[] args) {
        SpringApplication.run(TickeTideApplication.class, args);
    }

}
