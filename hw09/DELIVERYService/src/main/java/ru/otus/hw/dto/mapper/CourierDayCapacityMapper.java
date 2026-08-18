package ru.otus.hw.dto.mapper;

import org.jspecify.annotations.NonNull;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.CapacitySlotResponse;
import ru.otus.hw.dto.SetCourierCapacityRequest;
import ru.otus.hw.model.CourierDayCapacity;
import ru.otus.hw.model.CourierSlot;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {CourierSlotMapper.class})
public interface CourierDayCapacityMapper {

    /**
     * Маппятся courierCount и slots; capacityDate устанавливает сервис.
     */
    CourierDayCapacity toEntity(SetCourierCapacityRequest setCourierCapacityRequest);

    /**
     * capacity и reservedCount в слотах достраивает сервис через toBuilder.
     */
    CapacityResponse toCapacityResponse(CourierDayCapacity courierDayCapacity);

    @Mapping(target = "slotId", source = "id")
    CapacitySlotResponse toCapacitySlotBase(CourierSlot courierSlot);

    @AfterMapping
    default void linkSlots(@MappingTarget @NonNull CourierDayCapacity courierDayCapacity) {
        courierDayCapacity.getSlots().forEach(slot -> slot.setDayCapacity(courierDayCapacity));
    }
}
