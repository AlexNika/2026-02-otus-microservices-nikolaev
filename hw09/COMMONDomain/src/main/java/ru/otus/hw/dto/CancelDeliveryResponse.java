package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Result of a delivery reservation cancellation")
public record CancelDeliveryResponse(

        @Schema(description = "Order identifier", example = "100")
        Long orderId,

        @Schema(description = "Cancellation result")
        CancelDeliveryResult result
) {

    @Schema(description = "Cancellation result: CANCELLED - reservation was cancelled, "
            + "ALREADY_CANCELLED - reservation was already cancelled, NOT_FOUND - reservation not found")
    public enum CancelDeliveryResult {
        CANCELLED,
        ALREADY_CANCELLED,
        NOT_FOUND
    }
}
