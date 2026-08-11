package ru.otus.hw.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.dto.ProductCreateRequestDto;
import ru.otus.hw.dto.ProductPatchRequestDto;
import ru.otus.hw.dto.ProductResponseDto;
import ru.otus.hw.dto.ProductUpdateRequestDto;

import java.util.Optional;

public interface ProductService {

    Optional<ProductResponseDto> findProductById(Long id);

    ProductResponseDto getProductById(Long id);

    Page<ProductResponseDto> getAllProducts(Pageable pageable);

    Optional<ProductResponseDto> findProductBySku(String sku);

    ProductResponseDto getProductBySku(String sku);

    boolean existsBySku(String sku);

    Page<ProductResponseDto> searchProducts(String name, String article, Pageable pageable);

    ProductResponseDto createProduct(ProductCreateRequestDto productCreateRequestDto);

    ProductResponseDto updateProduct(Long id, ProductUpdateRequestDto dto);

    ProductResponseDto patchProduct(Long id, ProductPatchRequestDto dto);

    void deleteProduct(Long id);
}
