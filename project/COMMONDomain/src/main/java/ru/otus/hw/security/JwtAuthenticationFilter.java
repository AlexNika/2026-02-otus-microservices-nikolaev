package ru.otus.hw.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Общий JWT-фильтр всех микросервисов: локальная (stateless) валидация access-токена
 * по HMAC-подписи, без обращений в БД.
 *
 * <p>Поведение:
 * <ul>
 *   <li>нет заголовка {@code Authorization: Bearer ...} - пропускает запрос дальше
 *       (решение принимает цепочка: permitAll-пути проходят, защищённые получают 401);</li>
 *   <li>токен невалиден (подпись/срок/структура) - сразу 401 JSON {@link ru.otus.hw.dto.ErrorDto};</li>
 *   <li>токен валиден - в SecurityContext кладётся
 *       {@code UsernamePasswordAuthenticationToken(AuthPrincipal, null, ROLE_*-authorities)}:
 *       principal лежит именно в {@code Authentication.principal} (контракт для
 *       {@link OwnershipChecker} и {@code @AuthenticationPrincipal AuthPrincipal}).</li>
 * </ul>
 *
 * <p>Не является Spring-компонентом: экземпляр создаётся в SecurityConfig каждого сервиса
 * и подключается через {@code addFilterBefore(..., AuthorizationFilter.class)} в нужную цепочку.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(@NonNull JwtTokenProvider jwtTokenProvider, @NonNull ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String jwt = extractBearerToken(request);
        if (!StringUtils.hasText(jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthPrincipal principal;
        try {
            principal = jwtTokenProvider.parseAndVerify(jwt);
        } catch (RuntimeException e) {
            log.warn("Invalid JWT token for path {}: {}", request.getRequestURI(), e.getMessage());
            AuthSecurityDefaults.writeError(response, objectMapper,
                    HttpStatus.UNAUTHORIZED, "Invalid or expired JWT token");
            return;
        }

        List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(@NonNull HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
