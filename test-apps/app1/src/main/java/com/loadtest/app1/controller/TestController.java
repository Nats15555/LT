package com.loadtest.app1.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api")
public class TestController {
    
    private final Counter requestCounter;
    private final Timer responseTimer;
    private final Random random = new Random();
    
    public TestController(MeterRegistry meterRegistry) {
        this.requestCounter = Counter.builder("app1.requests.total")
                .description("Total number of requests")
                .tag("app", "test-app-1")
                .register(meterRegistry);
        
        this.responseTimer = Timer.builder("app1.response.time")
                .description("Response time in milliseconds")
                .tag("app", "test-app-1")
                .register(meterRegistry);
    }
    
    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return responseTimer.record(() -> {
            requestCounter.increment();

            try {
                Thread.sleep(random.nextInt(90) + 10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Hello from Test App 1");
            response.put("timestamp", System.currentTimeMillis());
            return response;
        });
    }
    
    @GetMapping("/cpu-intensive")
    public Map<String, Object> cpuIntensive() {
        return responseTimer.record(() -> {
            requestCounter.increment();

            long start = System.currentTimeMillis();
            long sum = 0;
            for (int i = 0; i < 1000000; i++) {
                sum += i * i;
            }
            long duration = System.currentTimeMillis() - start;
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "CPU intensive task completed");
            response.put("result", sum);
            response.put("duration", duration);
            return response;
        });
    }
}
