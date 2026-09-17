package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.otus.hw.dto.ProductCreateRequestDto;
import ru.otus.hw.dto.ProductPatchRequestDto;
import ru.otus.hw.dto.ProductResponseDto;
import ru.otus.hw.dto.ProductStockResponseDto;
import ru.otus.hw.dto.ProductUpdateRequestDto;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.service.ProductService;
import ru.otus.hw.service.ProductStockService;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/products")
@Tag(name = "Product API", description = "REST API for product management")
@SecurityRequirement(name = "basicAuth")
public class ProductResource {

    private final ProductService productService;

    private final ProductStockService productStockService;

    @PostMapping
    @Operation(summary = "Create a new product",
            description = "Create a new product and fill in the initial available quantity")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Product created successfully",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input data",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Admin role required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Product with the given SKU already exists",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponseDto> createProduct(@Valid @RequestBody
                                                            ProductCreateRequestDto productCreateRequestDto) {
        ProductResponseDto response = productService.createProduct(productCreateRequestDto);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Get product by ID",
            description = "Retrieves an product by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product found",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductResponseDto> getProductById(@PathVariable Long productId) {
        return ResponseEntity.ok(productService.getProductById(productId));
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Get product by SKU",
            description = "Retrieves an product by its SKU")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product found",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductResponseDto> getProductBySku(@PathVariable("sku") String sku) {
        return ResponseEntity.ok(productService.getProductBySku(sku));
    }

    @GetMapping("/{productId}/stocks")
    @Operation(summary = "Get stock of a specific product",
            description = "Retrieves the stock (available and reserved quantity) of a product by the product ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product stock found",
                    content = @Content(schema = @Schema(implementation = ProductStockResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "Product or its stock not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<ProductStockResponseDto> getProductStockByProductId(@PathVariable Long productId) {
        return ResponseEntity.ok(productStockService.getProductStockByProductId(productId));
    }

    @GetMapping("/search")
    @Operation(summary = "Search products",
            description = "Searches products by name and/or manufacturer article (case-insensitive substring match). " +
                    "Empty parameters return all products.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of matching products retrieved",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Page<ProductResponseDto>> searchProducts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String article,
            @ParameterObject
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(productService.searchProducts(name, article, pageable));
    }

    @GetMapping("/exists/{sku}")
    @Operation(summary = "Check product existence by SKU",
            description = "Lightweight check whether a product with the given SKU exists. " +
                    "Returns 204 if it exists, 404 otherwise.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Product with the given SKU exists"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Product with the given SKU does not exist")
    })
    public ResponseEntity<Void> existsBySku(@PathVariable("sku") String sku) {
        return productService.existsBySku(sku)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping
    @Operation(summary = "Get all products",
            description = "Retrieves a paginated list of all products")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of products retrieved",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Page<ProductResponseDto>> getAllProducts(
            @ParameterObject
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(productService.getAllProducts(pageable));
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Update product",
            description = "Updates a product by its ID and optionally updates its stock")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product updated successfully",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input data",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Admin role required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponseDto> updateProduct(@PathVariable Long productId,
                                                            @Valid @RequestBody
                                                            ProductUpdateRequestDto productUpdateRequestDto) {
        ProductResponseDto response = productService.updateProduct(productId, productUpdateRequestDto);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{productId}")
    @Operation(summary = "Partially update product",
            description = "Partially updates a product by its ID. Only non-null fields are updated. " +
                    "Optionally updates the available quantity of its stock.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product updated successfully",
                    content = @Content(schema = @Schema(implementation = ProductResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input data",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Admin role required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponseDto> patchProduct(@PathVariable Long productId,
                                                           @Valid @RequestBody
                                                           ProductPatchRequestDto productPatchRequestDto) {
        ProductResponseDto response = productService.patchProduct(productId, productPatchRequestDto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{productId}")
    @Operation(summary = "Delete a product",
            description = "Deletes a product by its ID and all associated reservations. " +
                    "ProductStock is removed via orphanRemoval cascade. " +
                    "Returns 204 on success, 404 if product not found.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Product deleted successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Admin role required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }
}
