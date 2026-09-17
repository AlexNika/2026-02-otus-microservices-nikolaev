package ru.otus.hw.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Локальные (stateless) операции с JWT access-токенами: проверка подписи/срока и генерация.
 *
 * <p>Никаких обращений в БД: подпись проверяется общим HMAC-секретом
 * {@code app.security.jwt-secret-key}, права читаются из claims токена.
 * Генерация ({@link #generateToken}) используется только AuthService - единственным Issuer'ом;
 * остальные сервисы вызывают только {@link #parseAndVerify}.
 *
 * <p>Claims: {@code sub} = email, {@code userId}, {@code roles} (через запятую),
 * {@code iat}, {@code exp}.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String CLAIM_USER_ID = "userId";

    public static final String CLAIM_ROLES = "roles";

    private final JwtSecurityProperties jwtSecurityProperties;

    private SecretKey signingKey;

    /**
     * Fail-fast при старте: пустой {@code JWT_SECRET_KEY} недопустим ни в одном сервисе.
     */
    @PostConstruct
    void init() {
        String secret = jwtSecurityProperties.getJwtSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.security.jwt-secret-key (JWT_SECRET_KEY) is not configured: "
                            + "JWT validation is impossible, refusing to start");
        }
        this.signingKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Генерация access-токена. Используется только AuthService (единственный Issuer).
     */
    public String generateToken(@NonNull String email, @NonNull Long userId, @NonNull List<String> roles) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + jwtSecurityProperties.getJwtAccessExpirationTime());
        return Jwts.builder()
                .subject(email)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLES, String.join(",", roles))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Локальная валидация подписи и срока действия; возвращает principal из claims.
     *
     * @throws io.jsonwebtoken.JwtException при невалидной подписи/сроке/структуре токена
     */
    public AuthPrincipal parseAndVerify(@NonNull String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String email = claims.getSubject();
        Long userId = claims.get(CLAIM_USER_ID, Long.class);
        String rolesClaim = claims.get(CLAIM_ROLES, String.class);
        List<String> roles = (rolesClaim == null || rolesClaim.isBlank())
                ? List.of()
                : List.of(rolesClaim.split(","));
        return new AuthPrincipal(userId, email, roles);
    }
}
