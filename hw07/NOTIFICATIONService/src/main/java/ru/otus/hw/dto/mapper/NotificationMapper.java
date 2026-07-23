package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.dto.NotificationEvent;
import ru.otus.hw.model.Notification;

import java.util.List;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface NotificationMapper {

    @Mapping(target = "notificationStatus", source = "status", qualifiedByName = "mapStatus")
    Notification toEntity(NotificationEvent event);

    NotificationDto toDto(Notification notification);

    List<NotificationDto> toDtoList(List<Notification> notifications);

    @Named("mapStatus")
    default Notification.NotificationStatus mapStatus(String status) {
        return "PLACED".equals(status) ? Notification.NotificationStatus.SUCCESS : Notification.NotificationStatus.FAILED;
    }
}
