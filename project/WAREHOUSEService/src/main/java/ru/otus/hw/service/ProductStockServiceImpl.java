package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.ProductStockResponseDto;
import ru.otus.hw.dto.mapper.ProductStockMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.repository.ProductStockRepository;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStockServiceImpl implements ProductStockService {

    private final ProductStockRepository productStockRepository;

    private final ProductStockMapper productStockMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductStockResponseDto> findProductStockById(Long id) {
        log.debug("Fetching product stock with id: {}", id);
        return productStockRepository.findById(id)
                .map(stock -> {
                    log.info("Product stock with id: {} found", id);
                    return productStockMapper.toProductStockResponseDto(stock);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductStockResponseDto getProductStockById(Long id) {
        return findProductStockById(id)
                .orElseThrow(() -> new NotFoundException("Product stock not found with id: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductStockResponseDto> findProductStockByProductId(Long productId) {
        log.debug("Fetching product stock for product id: {}", productId);
        return productStockRepository.findByProductId(productId)
                .map(stock -> {
                    log.info("Product stock for product id: {} found", productId);
                    return productStockMapper.toProductStockResponseDto(stock);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductStockResponseDto getProductStockByProductId(Long productId) {
        return findProductStockByProductId(productId)
                .orElseThrow(() -> new NotFoundException(
                        "Product stock not found for product id: " + productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductStockResponseDto> getProductStockByProductSku(String sku, @NonNull Pageable pageable) {
        log.info("Fetching all product stocks by product sku with pagination: page={}, size={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return productStockRepository.findByProductSkuContainingIgnoreCase(sku, pageable)
                .map(productStockMapper::toProductStockResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductStockResponseDto> getAllProductStock(@NonNull Pageable pageable) {
        log.info("Fetching all product stocks with pagination: page={}, size={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        return productStockRepository.findAll(pageable)
                .map(productStockMapper::toProductStockResponseDto);
    }
}
