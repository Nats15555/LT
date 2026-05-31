package com.loadtest.metrics.service;

public class KafkaOutboxPublishException extends RuntimeException {

    public KafkaOutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
