package ru.otus.hw.repository;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.model.ProductStock;

import java.util.Optional;


public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    @Override
    @NullMarked
    @EntityGraph("productStock-product-graph")
    Optional<ProductStock> findById(Long id);

    @Override
    @NullMarked
    @EntityGraph("productStock-product-graph")
    Page<ProductStock> findAll(Pageable pageable);

    @EntityGraph("productStock-product-graph")
    Optional<ProductStock> findByProductId(Long productId);

    @EntityGraph("productStock-product-graph")
    Page<ProductStock> findByProductSkuContainingIgnoreCase(String sku, Pageable pageable);
}