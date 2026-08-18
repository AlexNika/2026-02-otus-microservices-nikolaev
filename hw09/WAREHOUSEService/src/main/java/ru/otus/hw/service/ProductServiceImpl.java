package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.ProductCreateRequestDto;
import ru.otus.hw.dto.ProductPatchRequestDto;
import ru.otus.hw.dto.ProductResponseDto;
import ru.otus.hw.dto.ProductUpdateRequestDto;
import ru.otus.hw.dto.mapper.ProductMapper;
import ru.otus.hw.dto.mapper.ProductStockMapper;
import ru.otus.hw.exception.DuplicateResourceException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.model.Product;
import ru.otus.hw.model.ProductReservation;
import ru.otus.hw.model.ProductStock;
import ru.otus.hw.repository.ProductRepository;
import ru.otus.hw.repository.ProductReservationRepository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    private final ProductReservationRepository productReservationRepository;

    private final ProductMapper productMapper;

    private final ProductStockMapper stockMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductResponseDto> findProductById(Long id) {
        log.debug("Fetching product with id: {}", id);
        return productRepository.findById(id)
                .map(product -> {
                    log.info("Product with id: {} found", id);
                    return productMapper.toProductResponseDto(product);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDto getProductById(Long id) {
        return findProductById(id)
                .orElseThrow(() -> new NotFoundException("Product not found with id: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getAllProducts(@NonNull Pageable pageable) {
        log.info("Fetching all products with pagination: page={}, size={}, sort={}",
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        log.debug("Fetching all products");
        return productRepository.findAll(pageable)
                .map(productMapper::toProductResponseDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductResponseDto> findProductBySku(String sku) {
        if (sku == null || sku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU cannot be null or empty");
        }

        log.debug("Fetching product with SKU: {}", sku);
        return productRepository.findBySku(sku)
                .map(product -> {
                    log.info("Product with SKU: {} found", sku);
                    return productMapper.toProductResponseDto(product);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDto getProductBySku(String sku) {
        return findProductBySku(sku)
                .orElseThrow(() -> new NotFoundException("Product not found with SKU: " + sku));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsBySku(String sku) {
        if (sku == null || sku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU cannot be null or empty");
        }

        log.debug("Checking existence of product with SKU: {}", sku);
        boolean exists = productRepository.existsBySku(sku);
        log.info("Product with SKU: {} {}", sku, exists ? "exists" : "does not exist");
        return exists;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDto> searchProducts(String name, String article, @NonNull Pageable pageable) {
        String namePattern = name == null ? "" : name.trim();
        String articlePattern = article == null ? "" : article.trim();

        log.info("Searching products with name containing '{}', article containing '{}', " +
                        "pagination: page={}, size={}, sort={}",
                namePattern, articlePattern,
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());

        return productRepository
                .findByNameContainingIgnoreCaseOrManufacturerArticleContainingIgnoreCase(
                        namePattern, articlePattern, pageable)
                .map(productMapper::toProductResponseDto);
    }

    @Override
    @Transactional
    public ProductResponseDto createProduct(@NonNull ProductCreateRequestDto productCreateRequestDto) {
        String sku = productCreateRequestDto.sku();
        log.info("Creating product with SKU: {}", sku);

        Optional<Product> existingProduct = productRepository.findBySku(sku);
        if (existingProduct.isPresent()) {
            throw new DuplicateResourceException(
                    "Product with SKU already exists: " + sku);
        }

        ProductStock productStock = stockMapper.toProductStock(productCreateRequestDto.productStock());

        Product product = productMapper.toEntity(productCreateRequestDto);
        product.setProductStock(productStock);
        productStock.setProduct(product);

        Product savedProduct = productRepository.save(product);

        log.info("Product created successfully with id: {}", savedProduct.getId());
        return productMapper.toProductResponseDto(savedProduct);
    }

    @Override
    @Transactional
    public ProductResponseDto updateProduct(@NonNull Long id, @NonNull ProductUpdateRequestDto dto) {
        log.info("Updating product with ID: {}", id);

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found with id: " + id));

        String originalSku = existingProduct.getSku();

        productMapper.updateFromProductUpdateRequestDto(dto, existingProduct);
        existingProduct.setSku(originalSku);

        if (dto.productStock() != null) {
            ProductStock stock = existingProduct.getProductStock();
            if (stock == null) {
                stock = new ProductStock();
            }
            stockMapper.updateFromProductStockUpdateRequestDto(dto.productStock(), stock);
            stock.setProduct(existingProduct);
            existingProduct.setProductStock(stock);
        }

        Product savedProduct = productRepository.save(existingProduct);
        log.info("Product updated successfully with id: {}", savedProduct.getId());
        return productMapper.toProductResponseDto(savedProduct);
    }

    @Override
    @Transactional
    public ProductResponseDto patchProduct(@NonNull Long id, @NonNull ProductPatchRequestDto dto) {
        log.info("Partially updating product with ID: {}", id);

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found with id: " + id));

        String originalSku = existingProduct.getSku();

        productMapper.updateFromProductPatchRequestDto(dto, existingProduct);
        existingProduct.setSku(originalSku);

        if (dto.productStock() != null && dto.productStock().availableQuantity() != null) {
            ProductStock stock = existingProduct.getProductStock();
            if (stock == null) {
                stock = new ProductStock();
                stock.setProduct(existingProduct);
                existingProduct.setProductStock(stock);
            }
            stock.setAvailableQuantity(dto.productStock().availableQuantity());
        }

        Product savedProduct = productRepository.save(existingProduct);
        log.info("Product partially updated successfully with id: {}", savedProduct.getId());
        return productMapper.toProductResponseDto(savedProduct);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        log.info("Deleting product with id: {}", id);

        Product existingProduct = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found with id: " + id));

        List<ProductReservation> reservations = productReservationRepository.findByProductId(id);
        if (!reservations.isEmpty()) {
            log.info("Deleting {} reservations for product id: {}", reservations.size(), id);
            productReservationRepository.deleteAll(reservations);
        }

        productRepository.delete(existingProduct);
        log.info("Product deleted successfully with id: {}", id);
    }
}
