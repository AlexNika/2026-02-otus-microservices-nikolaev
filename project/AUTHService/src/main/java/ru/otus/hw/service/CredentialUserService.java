package ru.otus.hw.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.otus.hw.dto.AuthUserDto;

import java.util.Collection;
import java.util.List;

public interface CredentialUserService {

    Page<AuthUserDto> findAllUsers(Pageable pageable);

    AuthUserDto findUserById(Long id);

    List<AuthUserDto> findUsersByIds(Collection<Long> ids);

    void deleteUserById(Long id);

    void deleteAllUsers();
}
