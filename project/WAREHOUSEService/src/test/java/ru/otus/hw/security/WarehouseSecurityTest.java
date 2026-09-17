package ru.otus.hw.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.otus.hw.config.SecurityConfig;
import ru.otus.hw.controller.ProductResource;
import ru.otus.hw.dto.ProductCreateRequestDto;
import ru.otus.hw.dto.ProductResponseDto;
import ru.otus.hw.service.ProductService;
import ru.otus.hw.service.ProductStockService;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security-цепочки WAREHOUSEService: чтение товаров - любой аутентифицированный,
 * WRITE-операции (create/update/patch/delete) - только ADMIN.
 */
@WebMvcTest(ProductResource.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtSecurityProperties.class})
@TestPropertySource(properties = "app.security.jwt-secret-key=warehouse-security-mockmvc-test-secret-key-2026")
class WarehouseSecurityTest {

    private static final String VALID_CREATE_BODY = """
            {
              "manufacturerArticle": "A-100",
              "sku": "SKU-100",
              "name": "Test product",
              "description": "d",
              "price": 10.50,
              "productStock": {"availableQuantity": 5, "reservedQuantity": 0}
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductStockService productStockService;

    private String tokenFor(Long userId, String role) {
        return jwtTokenProvider.generateToken("user" + userId + "@example.com", userId, List.of(role));
    }

    @Test
    @DisplayName("GET /api/v1/products без JWT -> 401")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/products любым аутентифицированным пользователем -> 200")
    void shouldAllowReadForAnyAuthenticatedUser() throws Exception {
        when(productService.getAllProducts(any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor(7L, "USER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/products токеном USER -> 403")
    void shouldForbidCreateForRegularUser() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor(7L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST /api/v1/products токеном ADMIN -> 201 (bypass)")
    void shouldAllowCreateForAdmin() throws Exception {
        when(productService.createProduct(any(ProductCreateRequestDto.class)))
                .thenReturn(new ProductResponseDto(1L, "A-100", "SKU-100", "Test product", "d",
                        new BigDecimal("10.50")));

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + tokenFor(1L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }
}
