package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.models.AuthUser;
import ru.otus.hw.repository.AuthUserRepository;

import java.util.List;

/**
 * UserDetailsService для AuthenticationManager (login по email/password):
 * credentials читаются из {@code auth_users} + роли из {@code user_roles}.
 */
@Service
@RequiredArgsConstructor
public class AuthUserDetailsService implements UserDetailsService {

    private final AuthUserRepository authUserRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(@NonNull String email) throws UsernameNotFoundException {
        AuthUser authUser = authUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User with e-mail '%s' not found".formatted(email)));

        List<SimpleGrantedAuthority> authorities = authUser.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .toList();

        return new User(authUser.getEmail(), authUser.getPasswordHash(), authorities);
    }
}
