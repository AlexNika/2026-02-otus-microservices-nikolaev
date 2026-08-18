package ru.otus.hw.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.properties.InternalApiKeyConfig;
import ru.otus.hw.controller.InternalProductReservationResource;
import ru.otus.hw.service.ProductReservationService;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalProductReservationResource.class)
class GlobalExceptionHandlerTest {

    private static final String VALID_API_KEY = "testInternalApiKey";

    private static final String RESERVE_URL = "/internal/products/reservations";

    private static final String GET_BY_ORDER_URL = "/internal/products/reservations/1";

    private static final String VALID_RESERVE_BODY =
            "{\"orderId\":100,\"items\":[{\"productId\":1,\"quantity\":5,\"idempotencyKey\":42}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalApiKeyConfig internalApiKeyConfig;

    @MockitoBean
    private ProductReservationService productReservationService;

    @Test
    @DisplayName("должен вернуть 400 с кодом VALIDATION_FAILED при ConstraintViolationException")
    void shouldReturn400WithValidationFailedCodeOnConstraintViolation() throws Exception {
        Path propertyPath = mock(Path.class);
        when(propertyPath.toString()).thenReturn("availableQuantity");
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(propertyPath);
        when(violation.getMessage()).thenReturn("must be greater than or equal to 0");
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.reserve(any()))
                .thenThrow(new ConstraintViolationException("validation failed", Set.of(violation)));

        mockMvc.perform(post(RESERVE_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message")
                        .value("availableQuantity: must be greater than or equal to 0"));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом DATA_INTEGRITY_VIOLATION при DataIntegrityViolationException")
    void shouldReturn409WithDataIntegrityViolationCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.reserve(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        mockMvc.perform(post(RESERVE_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATA_INTEGRITY_VIOLATION))
                .andExpect(jsonPath("$.message").value("Data integrity violation"));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом CONCURRENT_MODIFICATION при OptimisticLockingFailureException")
    void shouldReturn409WithConcurrentModificationCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.reserve(any()))
                .thenThrow(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"));

        mockMvc.perform(post(RESERVE_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.CONCURRENT_MODIFICATION))
                .andExpect(jsonPath("$.message")
                        .value("Concurrent modification detected, retry the operation"));
    }

    @Test
    @DisplayName("должен вернуть 409 с кодом INSUFFICIENT_STOCK при недостатке стока")
    void shouldReturn409WithInsufficientStockCode() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.reserve(any()))
                .thenThrow(ProductReservationException.insufficientStock(1L, 5, 2));

        mockMvc.perform(post(RESERVE_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_RESERVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.INSUFFICIENT_STOCK))
                .andExpect(jsonPath("$.message")
                        .value("Not enough stock for product id: 1, requested: 5, available: 2"));
    }

    @Test
    @DisplayName("должен вернуть 400 с кодом MALFORMED_REQUEST при битом JSON в теле запроса")
    void shouldReturn400WithMalformedRequestCodeOnInvalidJson() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);

        mockMvc.perform(post(RESERVE_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                // language=text
                                "{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ErrorCodes.MALFORMED_REQUEST))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    @DisplayName("должен вернуть 404 без поля code при NotFoundException")
    void shouldReturn404WithoutCodeOnNotFoundException() throws Exception {
        when(internalApiKeyConfig.getInternalApiKey()).thenReturn(VALID_API_KEY);
        when(productReservationService.getByOrderId(1L))
                .thenThrow(new NotFoundException("Reservations not found for order id: 1"));

        mockMvc.perform(get(GET_BY_ORDER_URL)
                        .header("X-Internal-API-Key", VALID_API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
