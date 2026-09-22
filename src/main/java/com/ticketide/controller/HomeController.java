package com.ticketide.controller;

import com.ticketide.dto.response.Result;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final ConnectionFactory rabbitConnectionFactory;

    public HomeController(DataSource dataSource,
                          RedisConnectionFactory redisConnectionFactory,
                          ObjectProvider<ConnectionFactory> rabbitConnectionFactoryProvider) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.rabbitConnectionFactory = rabbitConnectionFactoryProvider.getIfAvailable();
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();

        // 数据库健康检查
        String dbStatus = "disconnected";
        try (Connection ignored = dataSource.getConnection()) {
            dbStatus = "connected";
        } catch (Exception e) {
            dbStatus = "disconnected";
        }
        health.put("database", dbStatus);

        // Redis健康检查
        String redisStatus = "disconnected";
        try {
            redisConnectionFactory.getConnection().close();
            redisStatus = "connected";
        } catch (Exception e) {
            redisStatus = "disconnected";
        }
        health.put("redis", redisStatus);

        // RabbitMQ健康检查
        String rabbitStatus = "disabled";
        if (rabbitConnectionFactory != null) {
            try {
                rabbitConnectionFactory.createConnection().close();
                rabbitStatus = "connected";
            } catch (Exception e) {
                rabbitStatus = "disconnected";
            }
        }
        health.put("rabbitmq", rabbitStatus);

        // 总体状态：数据库正常即为UP
        health.put("status", "connected".equals(dbStatus) ? "UP" : "DOWN");

        return Result.success(health);
    }
}

@Controller
class HomePageController {

    @GetMapping("/")
    public String home() {
        return "forward:/index.html";
    }
}
