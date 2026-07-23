package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.service.AccountService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/account")
@Tag(name = "Internal Account API", description = "Internal REST API for account creation (service-to-service)")
public class InternalAccountResource {

    private final AccountService accountService;

    @Operation(summary = "Create a new account (internal)",
            description = "Creates a new billing account for a user. Called by USERService during user registration.")
    @PostMapping
    public ResponseEntity<AccountResponseDto> createAccount(@RequestBody AccountCreateDto accountCreateDto) {
        AccountResponseDto accountResponseDto = accountService.createAccount(accountCreateDto);
        return ResponseEntity.ok(accountResponseDto);
    }
}
