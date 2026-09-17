package ru.otus.hw.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.dto.OrderCreateResult;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.exception.IdempotencyConflictException;
import ru.otus.hw.models.Order;
import ru.otus.hw.security.AuthPrincipal;
import ru.otus.hw.security.JwtTokenProvider;
import ru.otus.hw.service.OrderService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приёмка заголовка Idempotency-Key в POST /api/v1/order (с учётом JWT-безопасности):
 * коды 201/200/400/401/409 и обратная совместимость при отсутствии заголовка.
 * userId берётся из principal, поле userId в теле отсутствует (контракт).
 */
@WebMvcTest(OrderResource.class)
@Import(SecurityConfig.class)
class OrderResourceIdempotencyTest {

    private static final String ORDER_URL = "/api/v1/order";

    private static final Long USER_ID = 7L;

    private static final String VALID_KEY = "3f2b8c1a-9d4e-4a7f-8b2c-6e1d0a9b5c3d";

    private static final String VALID_BODY = """
            {
              "price": 250.00,
              "description": "test order",
              "productId": 11,
              "quantity": 3,
              "deliveryDate": "%s",
              "slotStart": "10:00",
              "slotEnd": "12:00"
            }
            """.formatted(LocalDate.now().plusDays(1));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userAuth() {
        AuthPrincipal principal = new AuthPrincipal(USER_ID, "john@example.com", List.of("USER"));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private OrderResponseDto responseDto(Order.OrderStatus status) {
        return new OrderResponseDto(100L, USER_ID, new BigDecimal("250.00"), "test order", 11L, 3,
                LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), status);
    }

    @Test
    @DisplayName("нет JWT - 401 Unauthorized")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(post(ORDER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("нет заголовка - createOrder(dto, userId, null), 201 Created (обратная совместимость)")
    void shouldCreateOrderWithoutKeyWhenHeaderAbsent() throws Exception {
        when(orderService.createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID), isNull()))
                .thenReturn(new OrderCreateResult(responseDto(Order.OrderStatus.PLACED),
                        OrderCreateResult.Kind.CREATED));

        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/order/100"))
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.orderStatus").value("PLACED"));

        verify(orderService).createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID), isNull());
    }

    @Test
    @DisplayName("валидный ключ, заказ создан - 201 Created")
    void shouldReturn201WhenOrderCreatedWithKey() throws Exception {
        when(orderService.createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID),
                eq(UUID.fromString(VALID_KEY))))
                .thenReturn(new OrderCreateResult(responseDto(Order.OrderStatus.PLACED),
                        OrderCreateResult.Kind.CREATED));

        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    @DisplayName("replay сохранённого успешного ответа - снова 201 с тем же заказом")
    void shouldReturn201OnReplayOfStoredResponse() throws Exception {
        when(orderService.createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID),
                eq(UUID.fromString(VALID_KEY))))
                .thenReturn(new OrderCreateResult(responseDto(Order.OrderStatus.PLACED),
                        OrderCreateResult.Kind.REPLAYED_COMPLETED));

        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.orderStatus").value("PLACED"));
    }

    @Test
    @DisplayName("replay незавершённого/проваленного заказа - 200 с текущим состоянием")
    void shouldReturn200OnReplayOfCurrentOrder() throws Exception {
        when(orderService.createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID),
                eq(UUID.fromString(VALID_KEY))))
                .thenReturn(new OrderCreateResult(responseDto(Order.OrderStatus.FAILED),
                        OrderCreateResult.Kind.REPLAYED_CURRENT));

        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.orderStatus").value("FAILED"));
    }

    @Test
    @DisplayName("невалидный UUID в заголовке - 400 MALFORMED_REQUEST")
    void shouldReturn400WhenKeyIsNotUuid() throws Exception {
        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(ErrorCodes.MALFORMED_REQUEST));
    }

    @Test
    @DisplayName("конфликт ключа - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldReturn409OnIdempotencyConflict() throws Exception {
        when(orderService.createOrder(any(ru.otus.hw.dto.OrderCreateDto.class), eq(USER_ID),
                eq(UUID.fromString(VALID_KEY))))
                .thenThrow(new IdempotencyConflictException(
                        "Idempotency-Key already used with a different request payload"));

        mockMvc.perform(post(ORDER_URL)
                        .with(userAuth())
                        .header("Idempotency-Key", VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT));
    }
}
