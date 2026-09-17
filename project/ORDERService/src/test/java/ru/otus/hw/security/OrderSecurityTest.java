package ru.otus.hw.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.controller.OrderResource;
import ru.otus.hw.dto.OrderResponseDto;
import ru.otus.hw.models.Order;
import ru.otus.hw.service.OrderService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки ORDERService: 401 без Bearer, 403 на чужие заказы,
 * 200 на свои, ADMIN bypass.
 */
@WebMvcTest(OrderResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, OwnershipChecker.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=order-security-mockmvc-test-secret-key-2026!!")
class OrderSecurityTest {

    private static final Long OWNER_ID = 7L;

    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private OrderService orderService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    private OrderResponseDto order(Long userId) {
        return new OrderResponseDto(100L, userId, new BigDecimal("250.00"), "test order", 11L, 3,
                LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0),
                Order.OrderStatus.PLACED);
    }

    @Test
    @DisplayName("GET /api/v1/order/{id} без JWT -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/order/100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/order/user/{userId}: свой список -> 200, чужой -> 403, ADMIN -> 200")
    void shouldApplyOwnershipOnOrdersByUser() throws Exception {
        when(orderService.getOrderByUserId(eq(OWNER_ID))).thenReturn(List.of(order(OWNER_ID)));

        mockMvc.perform(get("/api/v1/order/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(OWNER_ID));

        mockMvc.perform(get("/api/v1/order/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/order/user/{userId}", OWNER_ID)
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/order/{id}: свой заказ -> 200, чужой -> 403, ADMIN -> 200")
    void shouldApplyOwnershipOnGetOrderById() throws Exception {
        when(orderService.getOrderById(100L)).thenReturn(order(OWNER_ID));

        mockMvc.perform(get("/api/v1/order/100")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(OWNER_ID));

        mockMvc.perform(get("/api/v1/order/100")
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/order/100")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }
}
