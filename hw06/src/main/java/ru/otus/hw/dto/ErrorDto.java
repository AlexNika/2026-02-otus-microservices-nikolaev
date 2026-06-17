package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Error response details")
public class ErrorDto {
    @Schema(description = "Error message")
    private String message;
    @Schema(description = "HTTP status code")
    private Integer status;
    @Schema(description = "Timestamp when error occurred", example = "2026-04-05T10:00:00")
    private LocalDateTime timestamp;
}
