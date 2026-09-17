package ru.otus.hw.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Login request with credentials (email + password)")
public record LoginRequestDto(
        @Schema(description = "User's email address", example = "john.doe@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Email(message = "Email should be valid")
        @NotBlank(message = "Email cannot be blank")
        String email,

        @Schema(description = "User's password", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Password cannot be blank")
        String password
) {
}
