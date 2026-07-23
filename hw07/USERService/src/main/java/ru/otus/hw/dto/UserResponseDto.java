package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.otus.hw.models.User;

/**
 * DTO for {@link User}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO representing a user returned by the API")
public record UserResponseDto(@Schema(description = "Unique identifier of the user", example = "123",
                              accessMode = Schema.AccessMode.READ_ONLY)
                              Long id,
                              @Schema(description = "User's username (login identifier)", example = "johndoe",
                                      requiredMode = Schema.RequiredMode.REQUIRED)
                              @NotBlank(message = "Username is mandatory and cannot be null or empty")
                              @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
                              String userName,
                              @Schema(description = "User's first name", example = "John",
                                      requiredMode =  Schema.RequiredMode.NOT_REQUIRED)
                              @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
                              String firstName,
                              @Schema(description = "User's last name", example = "Doe",
                                      requiredMode =  Schema.RequiredMode.NOT_REQUIRED)
                              @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
                              String lastName,
                              @Schema(description = "User's email address", example = "john.doe@example.com",
                                      requiredMode = Schema.RequiredMode.REQUIRED)
                              @NotBlank(message = "Email is mandatory and cannot be null or empty")
                              @Email(message = "Email: '${validatedValue}' should be valid")
                              String email) {
}