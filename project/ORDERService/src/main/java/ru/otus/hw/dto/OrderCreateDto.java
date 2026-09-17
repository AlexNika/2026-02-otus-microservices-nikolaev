package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Тело запроса создания заказа. {@code userId} намеренно отсутствует: владелец заказа
 * берётся исключительно из claims access-JWT ( AuthPrincipal), поле в теле игнорируется -
 * клиент не может создать заказ от чужого имени.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request body for order creation; the order owner (userId) is taken "
        + "from the JWT claims and must not be sent in the body")
public record OrderCreateDto(@Schema(description = "Order price; positive, up to 19 integer "
                                     + "and 4 fraction digits",
                                     example = "250.00", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull @Digits(integer = 19, fraction = 4) @Positive
                             BigDecimal price,

                             @Schema(description = "Free-form order description",
                                     example = "Birthday cake with delivery")
                             String description,

                             @Schema(description = "Identifier of the product to order (WAREHOUSEService)",
                                     example = "11", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull @Positive
                             Long productId,

                             @Schema(description = "Ordered quantity; positive",
                                     example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull @Positive
                             Integer quantity,

                             @Schema(description = "Delivery date, yyyy-MM-dd; not in the past",
                                     example = "2026-09-10", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull @FutureOrPresent
                             @JsonFormat(pattern = "yyyy-MM-dd")
                             LocalDate deliveryDate,

                             @Schema(description = "Delivery time slot start, HH:mm",
                                     example = "10:00", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull
                             @JsonFormat(pattern = "HH:mm")
                             LocalTime slotStart,

                             @Schema(description = "Delivery time slot end, HH:mm; must be after slotStart",
                                     example = "12:00", requiredMode = Schema.RequiredMode.REQUIRED)
                             @NotNull
                             @JsonFormat(pattern = "HH:mm")
                             LocalTime slotEnd) {

    @JsonIgnore
    @AssertTrue(message = "slotEnd must be after slotStart")
    public boolean isSlotValid() {
        if (slotStart == null || slotEnd == null) {
            return true;
        }
        return slotEnd.isAfter(slotStart);
    }
}
