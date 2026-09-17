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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileFullUpdateDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.security.AuthPrincipal;
import ru.otus.hw.service.UserProfileService;

/**
 * Профиль текущего пользователя. {@code userId} берётся из {@link AuthPrincipal}
 * (claims access-токена), без DB-lookup по email - локальная stateless-валидация.
 * Помимо JWT-токена поддерживается HTTP Basic (email + пароль) - только для отладки
 * в Swagger-UI (валидация через запущенный AUTHService, см. {@code OpenApiConfig}).
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "User Profile",
        description = "Current user profile management: contacts (phone) and delivery addresses. "
                + "The user id is taken from the access-token claims, no request parameter needed.")
@SecurityRequirement(name = "basicAuth")
public class UserProfileResource {

    private final UserProfileService profileService;

    @GetMapping
    @Operation(summary = "Get current user profile",
            description = "Returns the profile of the currently authenticated user; "
                    + "the user id is resolved from the access-token claims")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile found",
                    content = @Content(schema = @Schema(implementation = UserProfileDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404",
                    description = "Profile not created yet (built asynchronously after registration)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<UserProfileDto> getCurrentProfile(@AuthenticationPrincipal AuthPrincipal principal) {
        UserProfileDto profile = profileService.getProfile(principal.userId());
        return ResponseEntity.ok(profile);
    }

    @PutMapping
    @Operation(summary = "Fully replace current user profile",
            description = "Full replacement of the profile: absent optional fields clear the stored "
                    + "values, the addresses collection is a full snapshot (null is treated as an "
                    + "empty list and removes all addresses)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile replaced",
                    content = @Content(schema = @Schema(implementation = UserProfileDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404",
                    description = "Profile not created yet (built asynchronously after registration)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Phone is already registered by another user",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<UserProfileDto> replaceCurrentProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UserProfileFullUpdateDto updateDto) {
        UserProfileDto replacedProfile = profileService.replaceProfile(principal.userId(), updateDto);
        return ResponseEntity.ok(replacedProfile);
    }

    @PatchMapping
    @Operation(summary = "Partially update current user profile",
            description = "Partial update of the profile: null fields keep the current values; "
                    + "addresses collection: null keeps current addresses, empty list removes all, "
                    + "otherwise a full snapshot replacement")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated",
                    content = @Content(schema = @Schema(implementation = UserProfileDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404",
                    description = "Profile not created yet (built asynchronously after registration)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Phone is already registered by another user",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<UserProfileDto> patchCurrentProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        UserProfileDto updatedProfile = profileService.updateProfile(principal.userId(), updateDto);
        return ResponseEntity.ok(updatedProfile);
    }
}
