package ru.otus.hw.repository;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.model.Product;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Override
    @NullMarked
    @EntityGraph("product-productStock-graph")
    Optional<Product> findById(Long id);

    @Override
    @NullMarked
    @EntityGraph("product-productStock-graph")
    Page<Product> findAll(Pageable pageable);

    @EntityGraph("product-productStock-graph")
    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    @EntityGraph("product-productStock-graph")
    Page<Product> findByNameContainingIgnoreCaseOrManufacturerArticleContainingIgnoreCase(
            String name, String manufacturerArticle, Pageable pageable);
}