package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ru.otus.hw.config.properties.BillingServiceUrlConfig;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final BillingServiceUrlConfig billingServiceUrlConfig;

    private final InternalApiKeyConfig internalApiKeyConfig;

    private final W3CTraceContextAdapter traceContextAdapter;

    @Bean
    public RestClient billingRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(billingServiceUrlConfig.getBillingServiceUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-API-Key", internalApiKeyConfig.getInternalApiKey())
                .requestInterceptor((request, body, execution) -> {
                    traceContextAdapter.injectCurrent(request.getHeaders(), (carrier, key, value) -> carrier.set(key, value));
                    return execution.execute(request, body);
                })
                .build();
    }
}
