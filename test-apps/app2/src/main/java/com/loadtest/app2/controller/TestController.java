package com.loadtest.app2.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api")
public class TestController {
    
    private final Counter requestCounter;
    private final Timer responseTimer;
    private final Random random = new Random();
    
    public TestController(MeterRegistry meterRegistry) {
        this.requestCounter = Counter.builder("app2.requests.total")
                .description("Total number of requests")
                .tag("app", "test-app-2")
                .register(meterRegistry);
        
        this.responseTimer = Timer.builder("app2.response.time")
                .description("Response time in milliseconds")
                .tag("app", "test-app-2")
                .register(meterRegistry);
    }
    
    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return responseTimer.record(() -> {
            requestCounter.increment();

            try {
                Thread.sleep(random.nextInt(130) + 20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return Map.of(
                    "message", "Hello from Test App 2",
                    "timestamp", System.currentTimeMillis());
        });
    }
    
    @GetMapping("/memory-intensive")
    public Map<String, Object> memoryIntensive() {
        return responseTimer.record(() -> {
            requestCounter.increment();

            long start = System.currentTimeMillis();
            int[] array = new int[1000000];
            for (int i = 0; i < array.length; i++) {
                array[i] = i * 2;
            }
            long duration = System.currentTimeMillis() - start;
            
            return Map.of(
                    "message", "Memory intensive task completed",
                    "arraySize", array.length,
                    "duration", duration);
        });
    }
}
