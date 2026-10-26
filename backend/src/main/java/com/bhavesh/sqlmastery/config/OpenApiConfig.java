package com.bhavesh.sqlmastery.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. The generated document is served at {@code /v3/api-docs}. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sqlMasteryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SQL Interview Mastery API")
                .version("1.0.0")
                .description("""
                        Backend for an interactive SQL learning application: a curriculum, an
                        interview problem bank, and an editor that runs real queries against a
                        real PostgreSQL database.

                        **There is no authentication.** That is a deliberate consequence of the
                        design rather than an omission. Every endpoint either serves public
                        teaching material, runs a read-only query as a role that can do nothing
                        but SELECT from two schemas of disposable sample data, or restores that
                        sample data to its published state. There is nothing here that belongs
                        to a particular person, so there is nobody to authenticate.

                        Learning progress lives in the browser, exactly so that this remains
                        true. See `docs/SECURITY.md` for the threat model and its limits.""")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
