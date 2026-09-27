package com.llogistics.order_service.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppConfigController {
    private final Environment environment;
    private final String temporalUiUrl;

    public AppConfigController(Environment environment,
            @Value("${app.temporal.ui-base-url:http://localhost:8233}") String temporalUiUrl) {
        this.environment = environment;
        this.temporalUiUrl = temporalUiUrl;
    }

    @GetMapping("/app-config")
    public AppConfig config() {
        return new AppConfig(environment.acceptsProfiles(Profiles.of("demo")), temporalUiUrl);
    }

    public record AppConfig(boolean demoEnabled, String temporalUiUrl) { }
}
