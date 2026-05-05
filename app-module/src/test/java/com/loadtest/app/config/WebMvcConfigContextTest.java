package com.loadtest.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebMvcConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WebMvcConfig.class);

    @Test
    void registersCorsConfigurer() {
        runner.run(ctx -> assertThat(ctx.getBeanNamesForType(WebMvcConfigurer.class)).isNotEmpty());
    }

    @Test
    void corsConfigurerRegistersApiPattern() {
        runner.run(ctx -> {
            WebMvcConfigurer cfg = ctx.getBean(WebMvcConfigurer.class);
            CorsRegistry reg = mock(CorsRegistry.class);
            CorsRegistration chain = mock(CorsRegistration.class);
            when(reg.addMapping(eq("/api/**"))).thenReturn(chain);
            when(chain.allowedOriginPatterns(any(String[].class))).thenReturn(chain);
            when(chain.allowedMethods(any(String[].class))).thenReturn(chain);
            when(chain.allowedHeaders(any(String[].class))).thenReturn(chain);
            cfg.addCorsMappings(reg);
            verify(reg).addMapping("/api/**");
            verify(chain).allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
            verify(chain).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            verify(chain).allowedHeaders("*");
        });
    }
}
