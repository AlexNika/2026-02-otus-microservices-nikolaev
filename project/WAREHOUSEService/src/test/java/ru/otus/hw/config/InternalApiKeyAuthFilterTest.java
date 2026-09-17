package ru.otus.hw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.controller.InternalProductReservationResource;
import ru.otus.hw.dto.ProductReservationListResponseDto;
import ru.otus.hw.security.JwtTokenProvider;
import ru.otus.hw.service.ProductReservationService;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalProductReservationResource.class)
@Import(SecurityConfig.class)
class InternalApiKeyAuthFilterTest {

    private static final String VALID_API_KEY = "testInternalApiKey";

    private static final String URL = "/internal/products/reservations/1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalApiKeyConfig internalApiKeyConfig;

    @MockitoBean
    private ProductReservationService productReservationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("должен вернуть 401 с понятным сообщением, если заголовок X-Internal-API-Key не задан")
    void shouldReturn401WhenApiKeyHeaderIsMissing() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message",
                        containsString("Missing required header 'X-Internal-API-Key'")));
    }

    @Test
    @DisplayName("должен вернуть 401 с понятным сообщением, если API-ключ неверный")
    void shouldReturn401WhenApiKeyIsInvalid() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(get(URL).header("X-Internal-API-Key", "wrongKey"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message",
                        containsString("Invalid internal API key")));
    }

    @Test
    @DisplayName("должен пропустить запрос, если API-ключ верный")
    void shouldPassWhenApiKeyIsValid() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.getByOrderId(1L))
                .thenReturn(new ProductReservationListResponseDto(1L, List.of()));

        mockMvc.perform(get(URL).header("X-Internal-API-Key", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(1));
    }
}
