package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product_stock")
@NamedEntityGraph(
        name = "productStock-product-graph",
        attributeNodes = @NamedAttributeNode("product")
)
public class ProductStock extends AuditableEntity<Long> {

    @OneToOne(mappedBy = "productStock", fetch = FetchType.LAZY)
    private Product product;

    @Min(0)
    @Builder.Default
    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity = 0;

    @Min(0)
    @Builder.Default
    @Column(name = "reserved_quantity")
    private Integer reservedQuantity = 0;

}