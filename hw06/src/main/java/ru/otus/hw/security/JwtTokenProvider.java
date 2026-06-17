package ru.otus.hw.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.JwtSecretKeyConfig;
import ru.otus.hw.exception.InvalidJwtException;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.models.User;
import ru.otus.hw.repository.UserRepository;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final UserRepository userRepository;

    private final JwtSecretKeyConfig jwtSecretKeyConfig;

    public String generateToken(@NonNull UserDetails userDetails) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtSecretKeyConfig.getJwtExpirationTime());

        String roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        User user = (User) userDetails;

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("userId", user.getId())
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        String email = getEmailFromToken(token);
        if (email == null) {
            log.warn("JWT token validation failed: email is missing or token is malformed");
            return false;
        }
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
            return false;
        }
    }

    public String getEmailFromToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            log.error("Invalid JWT token: {}", e.getMessage());
            return null;
        }

    }

    public User getUserFromToken(String token) {
        String email = getEmailFromToken(token);
        if (email == null) {
            throw new InvalidJwtException("JWT token is invalid: email (sub) claim is missing");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    private @NonNull SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecretKeyConfig.getJwtSecretKey().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
