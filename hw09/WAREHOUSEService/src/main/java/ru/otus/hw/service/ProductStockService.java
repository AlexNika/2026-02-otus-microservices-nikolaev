package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.ProductStockResponseDto;

import java.util.Optional;

public interface ProductStockService {

    Optional<ProductStockResponseDto> findProductStockById(Long id);

    ProductStockResponseDto getProductStockById(Long id);

    Optional<ProductStockResponseDto> findProductStockByProductId(Long productId);

    ProductStockResponseDto getProductStockByProductId(Long productId);

    @Transactional(readOnly = true)
    Page<ProductStockResponseDto> getProductStockByProductSku(String sku, @NonNull Pageable pageable);

    Page<ProductStockResponseDto> getAllProductStock(Pageable pageable);


}
