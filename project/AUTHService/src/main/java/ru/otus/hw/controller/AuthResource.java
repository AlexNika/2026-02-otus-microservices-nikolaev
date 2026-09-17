package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.AuthTokenResponseDto;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.LoginRequestDto;
import ru.otus.hw.dto.RefreshRequestDto;
import ru.otus.hw.dto.RegisterResponseDto;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.service.AuthenticationService;
import ru.otus.hw.service.RegistrationService;

/**
 * Публичный API AuthService (единственный Issuer токенов в системе):<br>
 * - register/login/refresh - без аутентификации;<br>
 * - logout - по access JWT.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, token refresh and logout")
public class AuthResource {

    private final RegistrationService registrationService;

    private final AuthenticationService authenticationService;

    @PostMapping("/register")
    @Operation(summary = "Register new user",
            description = "Saves credentials (role USER) and publishes UserCreatedEvent for projections")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered",
                    content = @Content(schema = @Schema(implementation = RegisterResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Email already exists",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<RegisterResponseDto> register(@Valid @RequestBody UserCreateDto userCreateDto) {
        RegisterResponseDto registered = registrationService.register(userCreateDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user",
            description = "Verifies credentials and returns an access + refresh token pair")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token pair issued",
                    content = @Content(schema = @Schema(implementation = AuthTokenResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Bad credentials",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AuthTokenResponseDto> login(@Valid @RequestBody LoginRequestDto loginRequest) {
        return ResponseEntity.ok(authenticationService.login(loginRequest));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh tokens",
            description = "Rotates the refresh token and returns a new access + refresh pair; " +
                    "reuse of a rotated token revokes all tokens of the user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rotated token pair issued",
                    content = @Content(schema = @Schema(implementation = AuthTokenResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Invalid, reused or expired refresh token",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AuthTokenResponseDto> refresh(@Valid @RequestBody RefreshRequestDto refreshRequest) {
        return ResponseEntity.ok(authenticationService.refresh(refreshRequest.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout user",
            description = "Removes the presented refresh token from the database (requires access JWT)")
    @SecurityRequirement(name = "basicAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Refresh token removed"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Invalid refresh token",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequestDto refreshRequest) {
        authenticationService.logout(refreshRequest.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
