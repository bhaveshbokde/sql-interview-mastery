package com.bhavesh.sqlmastery.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vite dev server, and nothing else.
 *
 * <p>In development the frontend runs on port 5173 and the backend on 8085, so
 * the browser treats them as different origins. In a built deployment the
 * frontend is static files served from the same origin as the API, and none of
 * this applies.
 *
 * <p>The origins are an explicit list rather than a wildcard. Not because the
 * API holds anything worth stealing — it serves a public curriculum and runs
 * read-only queries against disposable data — but because a wildcard here is a
 * habit that gets copied into a project where it does matter.
 */
@Configuration
@ConfigurationProperties(prefix = "sqlmastery.cors")
public class WebConfig implements WebMvcConfigurer {

    private List<String> allowedOrigins = List.of();

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "X-Client-Id", "X-Learner-Id")
                // No credentials are involved: there is no session and no
                // cookie, so allowing them would be meaningless as well as
                // incompatible with a strict origin list.
                .allowCredentials(false)
                .maxAge(3600);
    }
}
