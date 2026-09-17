package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO полной замены {@link ru.otus.hw.models.UserProfile} (PUT).
 * Все поля заменяются целиком: отсутствующие опциональные поля ({@code null})
 * очищают текущие значения.
 * <p>Семантика коллекции {@code addresses}: {@code null} трактуется как пустой список -
 * полная замена снимка: элементы с известным {@code addressId} обновляют существующие
 * записи, элементы без id создаются, отсутствующие в запросе записи удаляются.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for full replacement of a user profile (PUT). "
        + "Absent optional fields clear the stored values.")
public record UserProfileFullUpdateDto(
        @Schema(description = "User's username (display name)", example = "johndoe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Username is mandatory and cannot be null or empty")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String userName,

        @Schema(description = "User's first name; null clears the value", example = "John",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
        String firstName,

        @Schema(description = "User's last name; null clears the value", example = "Doe",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
        String lastName,

        @Schema(description = "User's birthdate; null clears the value", example = "1990-01-15T00:00:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Past(message = "Birthdate must be in the past")
        LocalDateTime birthdate,

        @Schema(description = "User's phone number (unique); null clears the value",
                example = "+79991234567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Pattern(regexp = "^\\+?[0-9]{10,15}$",
                message = "Phone must contain 10-15 digits, optionally prefixed with +")
        String phone,

        @Schema(description = "Delivery addresses full snapshot; null is treated as an empty list "
                + "and removes all addresses", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        List<UserAddressRequestDto> addresses
) {
}
