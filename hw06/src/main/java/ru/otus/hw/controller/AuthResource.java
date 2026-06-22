package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.AuthResponseDto;
import ru.otus.hw.dto.LoginRequestDto;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.models.User;
import ru.otus.hw.security.JwtTokenProvider;
import ru.otus.hw.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User registration and authentication")
public class AuthResource {
    
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    
    @PostMapping("/register")
    @Operation(summary = "Register new user", description = "Creates a new user account with USER role")
    public ResponseEntity<UserResponseDto> register(@Valid @RequestBody UserCreateDto userCreateDto) {
        UserResponseDto createdUser = userService.createUser(userCreateDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }
    
    @PostMapping("/login")
    @Operation(summary = "Authenticate user", description = "Authenticates user and returns JWT token")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.email(),
                        loginRequest.password()
                )
        );
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        
        User user = (User) authentication.getPrincipal();
        String jwt = jwtTokenProvider.generateToken(user);
        
        AuthResponseDto response = new AuthResponseDto(jwt, user.getId(), user.getEmail());
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/logout")
    @Operation(summary = "Logout user", description = "Logs out the current user (client should discard the token)")
    public ResponseEntity<Void> logout() {
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }
}
