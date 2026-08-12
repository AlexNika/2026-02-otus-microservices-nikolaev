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
public class AppProperties implements BillingServiceUrlConfig, WarehouseServiceUrlConfig, DeliveryServiceUrlConfig,
        InternalApiKeyConfig {

    @Getter(onMethod = @__(@Override))
    private String billingServiceUrl;

    @Getter(onMethod = @__(@Override))
    private String warehouseServiceUrl;

    @Getter(onMethod = @__(@Override))
    private String deliveryServiceUrl;

    @Getter(onMethod = @__(@Override))
    private String internalApiKey;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded BillingServiceUrl: BillingServiceUrl={}", billingServiceUrl);
        log.debug("Loaded WarehouseServiceUrl: WarehouseServiceUrl={}", warehouseServiceUrl);
        log.debug("Loaded DeliveryServiceUrl: DeliveryServiceUrl={}", deliveryServiceUrl);
        log.debug("Loaded InternalApiKey: InternalApiKey={}", internalApiKey == null ? "<not set>" : "***");
    }
}
