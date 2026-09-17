package ru.otus.hw.service;

import ru.otus.hw.dto.AuthTokenResponseDto;
import ru.otus.hw.dto.LoginRequestDto;

public interface AuthenticationService {

    AuthTokenResponseDto login(LoginRequestDto loginRequest);

    AuthTokenResponseDto refresh(String refreshToken);

    void logout(String refreshToken);
}
