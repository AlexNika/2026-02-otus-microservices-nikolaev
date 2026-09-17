package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.NotificationDto;
import ru.otus.hw.service.NotificationService;

import java.util.List;

/**
 * Чтение уведомлений: {@code ?userId=} должен совпадать с {@code userId} из JWT
 * (или ADMIN); {@code /all} - постраничный список уведомлений всех пользователей
 * только для ADMIN.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/notification")
@Tag(name = "Notification API", description = "REST API for reading user notifications")
@SecurityRequirement(name = "basicAuth")
public class NotificationResource {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("@authz.ownerOrAdmin(#userId)")
    @Operation(summary = "Get notifications of a user",
            description = "Retrieves notifications of the given user, newest first; "
                    + "accessible to the owner or an ADMIN")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Notifications found",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = NotificationDto.class)))),
            @ApiResponse(responseCode = "400", description = "userId is missing or invalid",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Caller is neither the owner nor an ADMIN",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public List<NotificationDto> getNotifications(
            @Parameter(description = "ID of the user whose notifications are requested", required = true)
            @RequestParam @NotNull(message = "userId is required") Long userId) {
        log.info("GET /notification?userId={}", userId);
        return notificationService.getNotificationsByUserId(userId);
    }

    /**
     * Постраничный список уведомлений всех пользователей - административное
     * представление (только роль ADMIN), сортировка и размер страницы задаются
     * {@link Pageable}; по умолчанию - самые свежие первыми.
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all notifications (ADMIN)",
            description = "Retrieves a paginated list of all users' notifications; ADMIN role required")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Page of notifications",
                    content = @Content(schema = @Schema(implementation = NotificationDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Page<NotificationDto>> getAllNotifications(
            @ParameterObject
            @PageableDefault(size = 20, sort = "created", direction = Sort.Direction.DESC) Pageable pageable) {
        log.info("GET /notification/all page={} size={}", pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(notificationService.getAllNotifications(pageable));
    }
}
