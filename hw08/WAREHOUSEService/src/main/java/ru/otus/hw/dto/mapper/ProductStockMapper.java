package ru.otus.hw.dto.mapper;

import org.jspecify.annotations.NonNull;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.ProductStockResponseDto;
import ru.otus.hw.dto.ProductStockUpdateRequestDto;
import ru.otus.hw.model.Product;
import ru.otus.hw.model.ProductStock;
import ru.otus.hw.dto.ProductStockCreateRequestDto;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductStockMapper {

    ProductStock toEntity(ProductStockResponseDto productStockResponseDto);

    default ProductStockResponseDto toProductStockResponseDto(@NonNull ProductStock productStock) {
        Product product = productStock.getProduct();
        return ProductStockResponseDto.builder()
                .id(product != null ? product.getId() : null)
                .manufacturerArticle(product != null ? product.getManufacturerArticle() : null)
                .sku(product != null ? product.getSku() : null)
                .name(product != null ? product.getName() : null)
                .description(product != null ? product.getDescription() : null)
                .price(product != null ? product.getPrice() : null)
                .availableQuantity(productStock.getAvailableQuantity())
                .reservedQuantity(productStock.getReservedQuantity())
                .build();
    }

    ProductStock toProductStock(ProductStockCreateRequestDto productStockCreateRequestDto);

    ProductStock toEntity(ProductStockUpdateRequestDto productStockUpdateRequestDto);

    ProductStockUpdateRequestDto toProductStockUpdateRequestDto(ProductStock productStock);

    void updateFromProductStockUpdateRequestDto(ProductStockUpdateRequestDto dto,
                                                @MappingTarget ProductStock productStock);

    @AfterMapping
    default void linkProduct(@MappingTarget @NonNull ProductStock productStock) {
        Product product = productStock.getProduct();
        if (product != null) {
            product.setProductStock(productStock);
        }
    }

}
