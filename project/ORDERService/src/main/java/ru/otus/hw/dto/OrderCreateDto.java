package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO for {@link Order}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateDto(@NotNull
                             Long userId,
                             @NotNull @Digits(integer = 19, fraction = 4) @Positive
                             BigDecimal price,
                             String description,
                             @NotNull @Positive
                             Long productId,
                             @NotNull @Positive
                             Integer quantity,
                             @NotNull @FutureOrPresent
                             @JsonFormat(pattern = "yyyy-MM-dd")
                             LocalDate deliveryDate,
                             @NotNull
                             @JsonFormat(pattern = "HH:mm")
                             LocalTime slotStart,
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
