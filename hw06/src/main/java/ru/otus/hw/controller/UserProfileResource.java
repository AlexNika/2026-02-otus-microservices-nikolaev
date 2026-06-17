package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
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
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.models.User;
import ru.otus.hw.service.UserProfileService;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Current user profile management")
public class UserProfileResource {
    
    private final UserProfileService profileService;
    
    @GetMapping
    @Operation(summary = "Get current user profile", description = "Returns the profile of the currently authenticated user")
    public ResponseEntity<UserProfileDto> getCurrentProfile(@AuthenticationPrincipal User user) {
        UserProfileDto profile = profileService.getProfile(user.getId());
        return ResponseEntity.ok(profile);
    }
    
    @PutMapping
    @Operation(summary = "Update current user profile", description = "Updates the profile of the currently authenticated user")
    public ResponseEntity<UserProfileDto> updateCurrentProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        UserProfileDto updatedProfile = profileService.updateProfile(user.getId(), updateDto);
        return ResponseEntity.ok(updatedProfile);
    }
    
    @PatchMapping
    @Operation(summary = "Partially update current user profile", description = "Partially updates the profile of the currently authenticated user")
    public ResponseEntity<UserProfileDto> patchCurrentProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UserProfileUpdateDto updateDto) {
        UserProfileDto updatedProfile = profileService.updateProfile(user.getId(), updateDto);
        return ResponseEntity.ok(updatedProfile);
    }
}
