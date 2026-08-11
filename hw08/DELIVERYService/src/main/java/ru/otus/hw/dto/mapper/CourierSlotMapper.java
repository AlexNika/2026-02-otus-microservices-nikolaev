package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.SlotIntervalDto;
import ru.otus.hw.dto.SlotIntervalRequestDto;
import ru.otus.hw.model.CourierSlot;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface CourierSlotMapper {

    /**
     * Маппятся только slotStart/slotEnd; dayCapacity проставляет сервис.
     */
    CourierSlot toEntity(SlotIntervalRequestDto slotIntervalRequestDto);

    /**
     * Маппятся только slotStart/slotEnd; dayCapacity проставляет сервис.
     */
    CourierSlot toEntity(SlotIntervalDto slotIntervalDto);

    SlotIntervalDto toSlotIntervalDto(CourierSlot courierSlot);
}
