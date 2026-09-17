package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.model.DeliveryReservation;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface DeliveryReservationMapper {

    /**
     * Резерв создаётся сервисом вручную (нужны courierSlot и assignedCourierNumber,
     * которых нет в request), поэтому обратные маппинги в request/entity не нужны.
     */
    @Mapping(target = "reservationId", source = "id")
    @Mapping(target = "date", expression = "java(entity.getCourierSlot().getDayCapacity().getCapacityDate())")
    @Mapping(target = "slotStart", expression = "java(entity.getCourierSlot().getSlotStart())")
    @Mapping(target = "slotEnd", expression = "java(entity.getCourierSlot().getSlotEnd())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    DeliveryReservationResponse toResponse(DeliveryReservation entity);
}
