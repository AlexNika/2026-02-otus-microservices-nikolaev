package ru.otus.hw.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.RoleDto;
import ru.otus.hw.service.RoleService;

import java.io.IOException;
import java.util.List;

/**
 * Управление ролями<br>
 * - доступ только роле ADMIN.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Role Management API", description = "Admin CRUD of roles")
@SecurityRequirement(name = "basicAuth")
public class RoleResource {

    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "Get all roles", description = "Returns the full list of roles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles found"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public List<RoleDto> getAllRoles() {
        return roleService.getAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role by id", description = "Returns a single role by its identifier")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role found",
                    content = @Content(schema = @Schema(implementation = RoleDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public RoleDto getRoleById(@PathVariable Long id) {
        return roleService.getOne(id);
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get role by name", description = "Returns a single role by its name")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role found",
                    content = @Content(schema = @Schema(implementation = RoleDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public RoleDto getRoleByName(@PathVariable String name) {
        return roleService.getOneByName(name);
    }

    @GetMapping("/by-ids")
    @Operation(summary = "Get roles by ids", description = "Returns the subset of roles matching the given ids")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles found"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public List<RoleDto> getManyRoles(@RequestParam List<Long> ids) {
        return roleService.getMany(ids);
    }

    @PostMapping
    @Operation(summary = "Create role", description = "Creates a new role and returns the saved entity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role created",
                    content = @Content(schema = @Schema(implementation = RoleDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public RoleDto create(@RequestBody RoleDto dto) {
        return roleService.create(dto);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Patch role", description = "Applies a JSON patch to the role and returns the updated entity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated",
                    content = @Content(schema = @Schema(implementation = RoleDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public RoleDto patch(@PathVariable Long id, @RequestBody JsonNode patchNode) throws IOException {
        return roleService.patch(id, patchNode);
    }

    @PatchMapping
    @Operation(summary = "Patch many roles",
            description = "Applies a JSON patch to all roles with the given ids and returns their ids")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles updated"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public List<Long> patchMany(@RequestParam List<Long> ids, @RequestBody JsonNode patchNode) throws IOException {
        return roleService.patchMany(ids, patchNode);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete role", description = "Deletes the role by its id and returns the deleted entity")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role deleted",
                    content = @Content(schema = @Schema(implementation = RoleDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public RoleDto delete(@PathVariable Long id) {
        return roleService.delete(id);
    }

    @DeleteMapping
    @Operation(summary = "Delete many roles", description = "Deletes all roles with the given ids")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles deleted"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public void deleteMany(@RequestParam List<Long> ids) {
        roleService.deleteMany(ids);
    }

    @DeleteMapping("/all")
    @Operation(summary = "Delete all roles", description = "Deletes every role")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All roles deleted"),
            @ApiResponse(responseCode = "403", description = "Access denied (ADMIN role required)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public void deleteAll() {
        roleService.deleteAll();
    }
}
