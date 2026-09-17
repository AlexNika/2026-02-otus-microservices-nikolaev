package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.ProductStockResponseDto;
import ru.otus.hw.service.ProductStockService;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/stocks")
@Tag(name = "Product Stock API", description = "REST API for product stock management")
@SecurityRequirement(name = "basicAuth")
public class ProductStockResource {

    private final ProductStockService productStockService;

    @GetMapping("/{stockId}")
    @Operation(summary = "Get product stock by ID",
            description = "Retrieves a product stock by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product stock found",
                    content = @Content(schema = @Schema(implementation = ProductStockResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Product stock not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductStockResponseDto> getProductStockById(@PathVariable Long stockId) {
        return ResponseEntity.ok(productStockService.getProductStockById(stockId));
    }

    @GetMapping
    @Operation(summary = "Get all product stocks",
            description = "Retrieves a paginated list of all product stocks")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of product stocks retrieved",
                    content = @Content(schema = @Schema(implementation = ProductStockResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Page<ProductStockResponseDto>> getAllProductStock(
            @ParameterObject
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(productStockService.getAllProductStock(pageable));
    }
}
