package ru.otus.hw.dto.mapper;

import org.jspecify.annotations.NonNull;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.ProductPatchRequestDto;
import ru.otus.hw.dto.ProductResponseDto;
import ru.otus.hw.dto.ProductUpdateRequestDto;
import ru.otus.hw.model.Product;
import ru.otus.hw.dto.ProductCreateRequestDto;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductMapper {

    Product toEntity(ProductCreateRequestDto productCreateRequestDto);

    ProductCreateRequestDto toProductCreateRequestDto(Product product);

    Product toEntity(ProductResponseDto productResponseDto);

    default ProductResponseDto toProductResponseDto(@NonNull Product product) {
        return ProductResponseDto.builder()
                .id(product.getId())
                .manufacturerArticle(product.getManufacturerArticle())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .build();
    }

    Product toEntity(ProductUpdateRequestDto productUpdateRequestDto);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromProductUpdateRequestDto(ProductUpdateRequestDto dto, @MappingTarget Product product);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateFromProductPatchRequestDto(ProductPatchRequestDto dto, @MappingTarget Product product);
}
