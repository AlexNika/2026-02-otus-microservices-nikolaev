package ru.otus.hw.service;

import ru.otus.hw.dto.RegisterResponseDto;
import ru.otus.hw.dto.UserCreateDto;

public interface RegistrationService {

    RegisterResponseDto register(UserCreateDto userCreateDto);
}
