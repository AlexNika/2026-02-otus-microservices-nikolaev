package ru.otus.hw.service;

import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileUpdateDto;

public interface UserProfileService {

    UserProfileDto getProfile(Long userId);

    UserProfileDto updateProfile(Long userId, UserProfileUpdateDto updateDto);
}
