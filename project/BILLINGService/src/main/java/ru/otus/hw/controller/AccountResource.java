package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.DepositRequestDto;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.security.AuthPrincipal;
import ru.otus.hw.service.AccountService;

import java.net.URI;

/**
 * Публичный API биллинга: доступен по JWT; ownership - владелец счёта или ADMIN
 * (ADMIN - bypass в {@link ru.otus.hw.security.OwnershipChecker}).
 * Помимо JWT-токена поддерживается HTTP Basic (email + пароль) - только для отладки
 * в Swagger-UI (валидация через запущенный AUTHService, см. {@code OpenApiConfig}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/account")
@Tag(name = "Account API", description = "Operations over user billing accounts")
@SecurityRequirement(name = "basicAuth")
public class AccountResource {

    private final AccountService accountService;

    @GetMapping("/{id}")
    @PostAuthorize("@authz.ownerOrAdmin(returnObject?.body?.userId())")
    @Operation(summary = "Get account by id",
            description = "Returns a single account by its primary identifier")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found",
                    content = @Content(schema = @Schema(implementation = AccountResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AccountResponseDto> getAccountById(@PathVariable Long id) {
        AccountResponseDto accountResponseDto = accountService.getById(id);
        return ResponseEntity.ok(accountResponseDto);
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("@billingAuthz.selfOrAdmin(#userId)")
    @Operation(summary = "Get account by user id",
            description = "Returns the account that belongs to the given user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found",
                    content = @Content(schema = @Schema(implementation = AccountResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AccountResponseDto> getAccountByUserId(@PathVariable Long userId) {
        AccountResponseDto accountResponseDto = accountService.getByUserId(userId);
        return ResponseEntity.ok(accountResponseDto);
    }

    @PostMapping
    @Operation(summary = "Create account",
            description = "Creates a new account for the authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created",
                    content = @Content(schema = @Schema(implementation = AccountResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Account already exists",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<AccountResponseDto> createAccount(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody(required = false) AccountCreateDto accountCreateDto) {
        AccountResponseDto accountResponseDto =
                accountService.createAccount(new AccountCreateDto(principal.userId()));
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(accountResponseDto.id())
                .toUri();
        return ResponseEntity.created(location).body(accountResponseDto);
    }

    @PostMapping("/{userId}/deposit")
    @PreAuthorize("@billingAuthz.selfOrAdmin(#userId)")
    @Operation(summary = "Deposit funds",
            description = "Adds funds to the account of the given user; supports an optional idempotency key")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Funds deposited",
                    content = @Content(schema = @Schema(implementation = DepositResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Inactive account or idempotency key conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<DepositResponseDto> deposit(@PathVariable Long userId,
                                                      @Valid @RequestBody DepositRequestDto depositRequestDto) {
        DepositResponseDto depositResponseDto = accountService.depositToAccount(userId, depositRequestDto.amount(),
                depositRequestDto.idempotencyKey());
        return ResponseEntity.ok(depositResponseDto);
    }

    @PostMapping("/{userId}/withdraw")
    @PreAuthorize("@billingAuthz.selfOrAdmin(#userId)")
    @Operation(summary = "Withdraw funds",
            description = "Withdraws funds from the account of the given user for an order")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Funds withdrawn",
                    content = @Content(schema = @Schema(implementation = WithdrawResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Access denied (not the owner and not ADMIN)",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient funds, inactive account or order conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    public ResponseEntity<WithdrawResponseDto> withdraw(@PathVariable Long userId,
                                                        @Valid @RequestBody WithdrawRequestDto request) {
        WithdrawResponseDto withdrawResponseDto = accountService.withdrawFromAccount(userId, request.amount(),
                request.orderId());
        return ResponseEntity.ok(withdrawResponseDto);
    }
}
