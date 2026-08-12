package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.dto.mapper.UserMapper;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.UserProfile;
import ru.otus.hw.repository.UserProfileRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    
    private final UserProfileRepository profileRepository;

    private final UserMapper mapper;
    
    @Override
    @Transactional(readOnly = true)
    public UserProfileDto getProfile(Long userId) {
        log.info("Fetching profile for user ID: {}", userId);
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return mapper.toProfileDto(profile);
    }
    
    @Override
    @Transactional
    public UserProfileDto updateProfile(Long userId, UserProfileUpdateDto updateDto) {
        log.info("Updating profile for user ID: {}", userId);
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        
        mapper.updateProfileFromDto(updateDto, profile);
        UserProfile updatedProfile = profileRepository.save(profile);
        log.info("Profile updated successfully for user ID: {}", userId);
        
        return mapper.toProfileDto(updatedProfile);
    }
}
