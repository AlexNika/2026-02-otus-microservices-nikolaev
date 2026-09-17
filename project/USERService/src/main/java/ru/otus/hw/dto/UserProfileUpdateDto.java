package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO частичного обновления {@link ru.otus.hw.models.UserProfile} (PATCH).
 * All fields are optional - only provided fields will be updated.
 * <p>Семантика коллекции {@code addresses}: {@code null} - не трогать; пустой список -
 * удалить все адреса; иначе полная замена снимка (по {@code addressId} каждого элемента).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for partial update of a user profile (PATCH). "
        + "Only provided fields are updated, null keeps the current value.")
public record UserProfileUpdateDto(
        @Schema(description = "User's username (display name)", example = "johndoe",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String userName,

        @Schema(description = "User's first name", example = "John",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
        String firstName,

        @Schema(description = "User's last name", example = "Doe",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
        String lastName,

        @Schema(description = "User's birthdate", example = "1990-01-15T00:00:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Past(message = "Birthdate must be in the past")
        LocalDateTime birthdate,

        @Schema(description = "User's phone number (unique); null keeps the current value",
                example = "+79991234567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Pattern(regexp = "^\\+?[0-9]{10,15}$",
                message = "Phone must contain 10-15 digits, optionally prefixed with +")
        String phone,

        @Schema(description = "Delivery addresses full snapshot; null keeps current addresses, "
                + "empty list removes all", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        List<UserAddressRequestDto> addresses
) {
}
