package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.otus.hw.models.User;
import ru.otus.hw.repository.UserRepository;

import static ru.otus.hw.util.ValidationMessages.ENTITY_NOT_FOUND_MESSAGE;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        ENTITY_NOT_FOUND_MESSAGE.getMessage(User.class.getSimpleName(), email)
                ));
    }
}
