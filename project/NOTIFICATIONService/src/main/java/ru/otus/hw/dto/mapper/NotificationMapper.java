package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.data.domain.Page;
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

    /**
     * Постраничный маппинг для ADMIN-представления: метаданные {@link Page}
     * (номер страницы, размер, общее количество) сохраняются.
     */
    default Page<NotificationDto> toDtoPage(Page<Notification> notifications) {
        return notifications.map(this::toDto);
    }

    /**
     * Статус сохраняется как есть; исключение - исторический маппинг заказа PLACED->SUCCESS
     * (совместимость со сценариями саги и коллекциями Postman). Статусы жизненного цикла
     * регистрации (USER_CREATED, USER_CREATION_FAILED, ACCOUNT_CREATED,
     * ACCOUNT_CREATION_FAILED, ACCOUNT_ACTIVATED) переносятся без изменений.
     */
    @Named("mapStatus")
    default String mapStatus(String status) {
        return "PLACED".equals(status) ? "SUCCESS" : status;
    }
}
