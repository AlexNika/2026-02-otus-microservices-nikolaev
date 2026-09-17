package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.AuthUserDto;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.service.CredentialUserService;

import java.util.List;

/**
 * Админ-управление credentials-записями пользователей (список/поиск/удаление).<br>
 * Регистрация вынесена в {@code POST /api/v1/auth/register}.<br>
 * - доступ только роле ADMIN.
 */
@RestController
@RequestMapping("api/v1/user")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "User Management API", description = "Admin view over credentials records")
@SecurityRequirement(name = "basicAuth")
public class UserResource {

    private final CredentialUserService credentialUserService;

    @GetMapping
    @Operation(summary = "Get all users (paged)",
            description = "Returns a page of credentials records without password_hash")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index",
                    schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0")),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1", defaultValue = "20")),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "Sort target: property name with optional direction (id | id,desc)",
                    schema = @Schema(type = "string", defaultValue = "id"))
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Users found"),
            @ApiResponse(responseCode = "400", description = "Invalid sort property",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public Page<AuthUserDto> getAllUsers(
            @Parameter(hidden = true) @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return credentialUserService.findAllUsers(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by id",
            description = "Returns a single credentials record without password_hash")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found",
                    content = @Content(schema = @Schema(implementation = AuthUserDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public AuthUserDto getUserById(@PathVariable Long id) {
        return credentialUserService.findUserById(id);
    }

    @GetMapping("/by-ids")
    @Operation(summary = "Get users by ids",
            description = "Returns the subset of credentials records matching the given ids")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Users found"),
            @ApiResponse(responseCode = "400", description = "Empty ids list",
                    content = @Content()),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<List<AuthUserDto>> getManyUsers(@RequestParam(name = "ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(credentialUserService.findUsersByIds(ids));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user credentials by id",
            description = "Removes the credentials record and its refresh tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User credentials deleted"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AuthUserDto> deleteUser(@PathVariable Long id) {
        credentialUserService.deleteUserById(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping
    @Operation(summary = "Delete all user credentials",
            description = "Removes every credentials record and all refresh tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "All user credentials deleted"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<Void> deleteAllUsers() {
        credentialUserService.deleteAllUsers();
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
