package ru.otus.hw.dto;

public record AuthResponseDto(
        String token,
        String tokenType,
        Long userId,
        String email
) {
    public AuthResponseDto(String token, Long userId, String email) {
        this(token, "Bearer", userId, email);
    }
}
