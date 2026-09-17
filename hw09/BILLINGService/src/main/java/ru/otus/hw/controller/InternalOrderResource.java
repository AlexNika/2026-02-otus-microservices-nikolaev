package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ErrorDto;
import ru.otus.hw.dto.RefundRequestDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.service.AccountService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/order")
@Tag(name = "Internal Order API", description = "Internal REST API for order processing (service-to-service)")
public class InternalOrderResource {

    private final AccountService accountService;

    @Operation(summary = "Withdraw funds for order (internal)",
            description = "Withdraws funds from user's account for an order. Idempotent by orderId. "
                    + "Called by ORDERService during order processing.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Funds withdrawn",
                    content = @Content(schema = @Schema(implementation = WithdrawResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or invalid amount",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient funds, account inactive or conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawResponseDto> withdraw(@Valid @RequestBody WithdrawRequestDto request) {
        WithdrawResponseDto withdrawResponseDto = accountService.withdrawFromAccount(
                request.userId(),
                request.amount(),
                request.orderId()
        );
        return ResponseEntity.ok(withdrawResponseDto);
    }

    @Operation(summary = "Refund funds for cancelled order (internal)",
            description = "Refunds funds back to user's account for a cancelled order. Idempotent by orderId; "
                    + "no-op (200) when no withdrawal exists for the order. "
                    + "Called by ORDERService when order is cancelled.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Funds refunded (or no-op refund)",
                    content = @Content(schema = @Schema(implementation = RefundResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or invalid amount",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Account inactive or conflict",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostMapping("/refund")
    public ResponseEntity<RefundResponseDto> refund(@Valid @RequestBody RefundRequestDto request) {
        RefundResponseDto refundResponseDto = accountService.refundToAccount(
                request.userId(),
                request.amount(),
                request.orderId()
        );
        return ResponseEntity.ok(refundResponseDto);
    }

    @Operation(summary = "Withdrawal status for order (internal)",
            description = "Read-only check whether a withdrawal exists for the order. No side effects — "
                    + "used by ORDERService saga recovery to restore the actual state.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Withdrawal status",
                    content = @Content(schema = @Schema(implementation = WithdrawStatusDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @GetMapping("/{orderId}/withdraw-status")
    public ResponseEntity<WithdrawStatusDto> withdrawStatus(@PathVariable Long orderId) {
        return ResponseEntity.ok(accountService.getWithdrawStatus(orderId));
    }
}
