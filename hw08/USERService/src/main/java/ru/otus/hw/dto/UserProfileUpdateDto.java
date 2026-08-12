package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * DTO for updating {@link ru.otus.hw.models.UserProfile}
 * All fields are optional - only provided fields will be updated
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for updating a user profile. All fields are optional.")
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
        LocalDateTime birthdate
) {
}
