package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for {@link ru.otus.hw.models.User}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserUpdateDto(@NotBlank(message = "Username cannot be null or empty")
                            @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
                            String userName,
                            @Size(min = 1, max = 100, message = "First name must be between 1 and 100 characters")
                            String firstName,
                            @Size(min = 1, max = 100, message = "Last name must be between 1 and 100 characters")
                            String lastName,
                            @NotBlank(message = "Email cannot be null or empty")
                            @Email(message = "Email: '${validatedValue}' should be valid")
                            String email) {
}