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
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties implements JwtSecretKeyConfig, BCryptConfig {

    @Getter(onMethod = @__(@Override))
    private String jwtSecretKey;

    @Getter(onMethod = @__(@Override))
    private long jwtExpirationTime;

    @Getter(onMethod = @__(@Override))
    private int bcryptIterations;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded SecurityProperties: jwtSecretKey=[{}], jwtExpirationTime={}ms, bcryptIterations={}",
                "***", jwtExpirationTime, bcryptIterations);
    }
}
