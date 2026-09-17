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
import ru.otus.hw.controller.DeliveryReservationResource;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.model.DeliveryReservation;
import ru.otus.hw.repository.DeliveryReservationRepository;
import ru.otus.hw.service.DeliveryReservationService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки DELIVERYService: бронь видна её владельцу или ADMIN, чужая -> 403.
 */
@WebMvcTest(DeliveryReservationResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class, OwnershipChecker.class, DeliveryAuthz.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=delivery-security-mockmvc-test-secret-key-2026")
class DeliverySecurityTest {

    private static final Long OWNER_ID = 7L;

    private static final Long OTHER_USER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private DeliveryReservationService deliveryReservationService;

    @MockitoBean
    private DeliveryReservationRepository deliveryReservationRepository;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    private DeliveryReservation reservationOwnedBy(Long userId) {
        DeliveryReservation reservation = DeliveryReservation.builder()
                .userId(userId)
                .build();
        reservation.setId(55L);
        return reservation;
    }

    private DeliveryReservationResponse response() {
        return DeliveryReservationResponse.builder()
                .reservationId(55L)
                .orderId(100L)
                .date(LocalDate.now().plusDays(1))
                .slotStart(LocalTime.of(10, 0))
                .slotEnd(LocalTime.of(12, 0))
                .assignedCourierNumber(1)
                .status("RESERVED")
                .build();
    }

    @Test
    @DisplayName("GET брони по orderId без JWT -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/delivery/orders/100/reservation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET брони по orderId: владелец -> 200, чужой -> 403, ADMIN -> 200")
    void shouldApplyOwnershipOnGetByOrderId() throws Exception {
        when(deliveryReservationRepository.findByOrderId(100L))
                .thenReturn(Optional.of(reservationOwnedBy(OWNER_ID)));
        when(deliveryReservationService.getByOrderId(eq(100L))).thenReturn(response());

        mockMvc.perform(get("/api/v1/delivery/orders/100/reservation")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(100));

        mockMvc.perform(get("/api/v1/delivery/orders/100/reservation")
                        .header("Authorization", "Bearer " + tokenFor(OTHER_USER_ID, "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/delivery/orders/100/reservation")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("бронь без владельца (историческая): обычному пользователю -> 403, ADMIN -> 200")
    void shouldDenyHistoricalReservationForRegularUser() throws Exception {
        when(deliveryReservationRepository.findByOrderId(101L))
                .thenReturn(Optional.of(reservationOwnedBy(null)));
        when(deliveryReservationService.getByOrderId(eq(101L))).thenReturn(response());

        mockMvc.perform(get("/api/v1/delivery/orders/101/reservation")
                        .header("Authorization", "Bearer " + tokenFor(OWNER_ID, "USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/delivery/orders/101/reservation")
                        .header("Authorization", "Bearer " + tokenFor(99L, "ADMIN")))
                .andExpect(status().isOk());
    }
}
