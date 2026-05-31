package ru.otus.hw.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserPatchDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.UserUpdateDto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserService {

    Optional<UserResponseDto> findUserById(Long id);

    List<UserResponseDto> findUsersByIds(Collection<Long> ids);

    Page<UserResponseDto> findAllUsers(Pageable pageable);

    UserResponseDto createUser(UserCreateDto userCreateDto);

    UserResponseDto updateUserById(Long id, UserUpdateDto userUpdateDto);

    UserResponseDto updateUserByIdPartially(Long id, UserPatchDto dto);

    void deleteUserById(long id);

    void deleteAllUsers();
}
