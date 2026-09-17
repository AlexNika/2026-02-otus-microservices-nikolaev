package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Контракт регистрации пользователя. Источник - AuthService ({@code POST /api/v1/auth/register}):
 * credentials (email/пароль/роли) сохраняет AuthService, профильные поля
 * ({@code userName..addresses}) несёт расширенный {@link UserCreatedEvent} потребителям
 * (USERService - проекция профиля и адресов, BILLINGService - только {@code userId}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "DTO for creating a new user. The login is an email.")
public record UserCreateDto(@Schema(description = "User's username (for profile)", example = "johndoe",
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
                            @Schema(description = "User's email address (as identifier)",
                                    example = "john.doe@example.com",
                                    requiredMode = Schema.RequiredMode.REQUIRED)
                            @NotBlank(message = "Email is mandatory and cannot be null or empty")
                            @Email(message = "Email: '${validatedValue}' should be valid")
                            String email,
                            @Schema(description = "User's password (min 8 chars, must contain uppercase, lowercase, " +
                                    "digit, and special character @#$%^&+=)",
                                    example = "Password123!",
                                    requiredMode = Schema.RequiredMode.REQUIRED)
                            @NotBlank(message = "Password is mandatory and cannot be null or empty")
                            @Size(min = 8, max = 64, message = "Password must be between 8 and 64 characters")
                            @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#$%^&+=])(?=\\S+$).{8,64}$",
                                    message = "Password must contain at least one digit, one lowercase, " +
                                            "one uppercase letter, and one special character (@#$%^&+=), and no spaces")
                            String password,
                            @Schema(description = "User's phone number (unique)", example = "+79991234567",
                                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                            @Pattern(regexp = "^\\+?[0-9]{10,15}$",
                                    message = "Phone must contain 10-15 digits, optionally prefixed with +")
                            String phone,
                            @Schema(description = "Initial delivery addresses snapshot (0..N)",
                                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                            @Valid
                            List<UserAddressRequestDto> addresses) {
}
