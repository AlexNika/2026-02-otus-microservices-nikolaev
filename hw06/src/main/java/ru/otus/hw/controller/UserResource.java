package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserPatchDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.UserUpdateDto;
import ru.otus.hw.exception.UserNotFoundException;
import ru.otus.hw.service.UserService;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/user")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Management API", description = "REST API for managing user")
public class UserResource {

    private final UserService userService;

    @Operation(summary = "Get all users",
            description = "Retrieves a paginated list of all users. Returns an empty page if no users exist.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved paginated list of users"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters")
    })
    @GetMapping
    public ResponseEntity<Page<UserResponseDto>> getAllUsers(Pageable pageable) {
        Page<UserResponseDto> users = userService.findAllUsers(pageable);
        return ResponseEntity.ok(users);
    }

    @Operation(summary = "Get user by ID",
            description = "Retrieves the details of a specific user by their unique identifier.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved user details"),
            @ApiResponse(responseCode = "400", description = "Invalid user ID format",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDto> getUserById(@PathVariable Long id) {
        return userService.findUserById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Operation(summary = "Get users by IDs",
            description = "Retrieves a list of users based on the provided list of user identifiers.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved list of users"),
            @ApiResponse(responseCode = "400", description = "Invalid or empty list of user IDs",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @GetMapping(params = "ids")
    public ResponseEntity<List<UserResponseDto>> getManyUsers(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        List<UserResponseDto> users = userService.findUsersByIds(ids);
        return ResponseEntity.ok(users);
    }

    @Operation(summary = "Create a new user",
            description = "Creates a new user with the provided details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "User successfully created"),
            @ApiResponse(responseCode = "400", description = "Validation error or invalid input data",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Duplication error (username or email already exists)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))


    })
    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(
            @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "User creation request payload",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserCreateDto.class),
                            examples = {
                                    @ExampleObject(name = "Valid User Creation",
                                            value = """
                                                    {
                                                      "userName": "alice_johnson",
                                                      "firstName": "Alice",
                                                      "lastName": "Johnson",
                                                      "email": "alice.j@example.com"
                                                    }
                                                    """)
                            }
                    )
            )
            @RequestBody UserCreateDto userCreateDto) {
        UserResponseDto userResponseDto = userService.createUser(userCreateDto);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(userResponseDto.id())
                .toUri();
        return ResponseEntity.created(location).body(userResponseDto);
    }

    @Operation(summary = "Update a user by ID",
            description = "Updates all details of an existing user identified by their unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User successfully updated"),
            @ApiResponse(responseCode = "400", description = "Invalid user ID or validation error in update data",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDto> updateUser(
            @PathVariable Long id,
            @Valid
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "User update request payload",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserUpdateDto.class),
                            examples = {
                                    @ExampleObject(name = "Valid User Update",
                                            value = """
                                                    {
                                                      "userName": "alice_johnson",
                                                      "firstName": "Alice",
                                                      "lastName": "Johnson",
                                                      "email": "alice.j@example.com"
                                                    }
                                                    """)
                            }
                    )
            )
            @RequestBody UserUpdateDto userUpdateDto) {
        UserResponseDto updatedUser = userService.updateUserById(id, userUpdateDto);
        return ResponseEntity.ok(updatedUser);
    }

    @Operation(summary = "Partially update a user by ID",
            description = "Partially updates an existing user's details identified by their unique ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User successfully updated"),
            @ApiResponse(responseCode = "400", description = "Invalid user ID or validation error in patch data",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @PatchMapping("/{id}")
    public ResponseEntity<UserResponseDto> patchUser(@PathVariable Long id,
                                                     @Valid @RequestBody UserPatchDto userPatchDto) {
        UserResponseDto patchedUser = userService.updateUserByIdPartially(id, userPatchDto);
        return ResponseEntity.ok(patchedUser);
    }

    @Operation(summary = "Delete a user by ID",
            description = "Deletes a specific user identified by their unique identifier.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "User successfully deleted"),
            @ApiResponse(responseCode = "400", description = "Invalid user ID format",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error occurred during deletion",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUserById(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete all users",
            description = "Deletes all users from the system. Use with caution.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "All users successfully deleted"),
            @ApiResponse(responseCode = "500", description = "Internal server error occurred during deletion",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorDto.class)))
    })
    @DeleteMapping
    public ResponseEntity<Void> deleteAllUsers() {
        userService.deleteAllUsers();
        return ResponseEntity.noContent().build();
    }

}

