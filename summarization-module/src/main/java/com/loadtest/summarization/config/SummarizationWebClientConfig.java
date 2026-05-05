package com.loadtest.summarization.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class SummarizationWebClientConfig {

    @Bean(name = "summarizationLlmWebClient")
    public WebClient summarizationLlmWebClient(
            @Value("${loadtest.summarization.http.connect-timeout-millis:15000}") int connectTimeoutMillis,
            @Value("${loadtest.summarization.http.response-timeout-seconds:600}") int responseTimeoutSeconds,
            @Value("${loadtest.summarization.http.max-in-memory-mb:16}") int maxInMemoryMb) {
        HttpClient reactorHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
        int maxBytes = Math.max(1, maxInMemoryMb) * 1024 * 1024;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
        return WebClient.builder()
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(reactorHttpClient))
                .build();
    }
}
