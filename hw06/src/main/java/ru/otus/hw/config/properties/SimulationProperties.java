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
public class SimulationProperties implements SimulationConfig {

    @Getter(onMethod = @__(@Override))
    private boolean isSimulationErrorsBypassEnabled;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded SimulationProperties: isSimulationErrorsBypassEnabled={}", isSimulationErrorsBypassEnabled);
    }
}
