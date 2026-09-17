package ru.otus.hw.config.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Setter
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties implements InternalApiKeyConfig,  AuthServiceUrlConfig {

    @Getter(onMethod = @__(@Override))
    private String internalApiKey;

    @Getter(onMethod = @__(@Override))
    private String authServiceUrl;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded InternalApiKey: InternalApiKey={}", internalApiKey == null ? "<not set>" : "***");
        log.debug("Loaded AuthServiceUrl: authServiceUrl={}", authServiceUrl);
    }
}
