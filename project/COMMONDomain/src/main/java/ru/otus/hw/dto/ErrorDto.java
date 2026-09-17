package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
@Schema(description = "Error response details")
public record ErrorDto(
        @Schema(description = "Error message")
        String message,
        @Schema(description = "HTTP status code")
        Integer status,
        @Schema(description = "Timestamp when error occurred", example = "2026-04-05T10:00:00")
        LocalDateTime timestamp,
        @Schema(description = "Machine-readable error code", example = "INSUFFICIENT_STOCK")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String code
) {}
