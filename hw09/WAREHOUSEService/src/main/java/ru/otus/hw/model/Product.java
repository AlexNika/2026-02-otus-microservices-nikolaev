package ru.otus.hw.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "products",
        indexes = {
                @Index(name = "idx_products_sku", columnList = "sku")
        }
)
@NamedEntityGraph(
        name = "product-productStock-graph",
        attributeNodes = @NamedAttributeNode("productStock")
)
public class Product extends AuditableEntity<Long> {

    @Column(name = "manufacturer_article", length = 128)
    private String manufacturerArticle;

    @Column(name = "sku", length = 64, nullable = false, unique = true)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Min(0)
    @Builder.Default
    @Column(name = "price", precision = 19, scale = 4, nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @OneToOne(
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @JoinColumn(
            name = "product_stock_id",
            foreignKey = @ForeignKey(name = "fk_product_product_stock"),
            unique = true
    )
    private ProductStock productStock;
}