package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ru.otus.hw.config.properties.AppProperties;
import ru.otus.hw.config.properties.InternalApiKeyConfig;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final AppProperties appProperties;

    private final InternalApiKeyConfig internalApiKeyConfig;

    @Bean
    public RestClient billingRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(appProperties.getBillingServiceUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-API-Key", internalApiKeyConfig.getInternalApiKey())
                .build();
    }

    @Bean
    public RestClient warehouseRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(appProperties.getWarehouseServiceUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-API-Key", internalApiKeyConfig.getInternalApiKey())
                .build();
    }

    @Bean
    public RestClient deliveryRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(appProperties.getDeliveryServiceUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-API-Key", internalApiKeyConfig.getInternalApiKey())
                .build();
    }
}
