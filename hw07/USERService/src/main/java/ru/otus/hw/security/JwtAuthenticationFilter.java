package ru.otus.hw.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.models.User;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        log.debug("JwtAuthenticationFilter: Processing request to {}", request.getRequestURI());
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                if (!jwtTokenProvider.validateToken(jwt)) {
                    throw new BadCredentialsException("Invalid JWT token");
                }

                try {
                    User user = jwtTokenProvider.getUserFromToken(jwt);

                    var authentication = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            user.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (UsernameNotFoundException e) {
                    throw new UsernameNotFoundException("User not found: " + e.getMessage());
                }
            }
        } catch (AuthenticationException ex) {
            log.error("Authentication failed", ex);
            setErrorResponse(response, ex.getMessage(), HttpServletResponse.SC_UNAUTHORIZED);
            return;
        } catch (Exception ex) {
            log.warn("Unexpected exception", ex);
        }

        filterChain.doFilter(request, response);
    }

    private @Nullable String getJwtFromRequest(@NonNull HttpServletRequest request) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private void setErrorResponse(@NonNull HttpServletResponse response, String message, int statusCode) {
        response.setStatus(statusCode);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"api\"");
        }
        try {
            ErrorDto error = ErrorDto.builder()
                    .status(statusCode)
                    .message(message)
                    .timestamp(LocalDateTime.now())
                    .build();
            objectMapper.writeValue(response.getOutputStream(), error);
        } catch (IOException e) {
            log.error("Failed to write error response", e);
        }
    }
}