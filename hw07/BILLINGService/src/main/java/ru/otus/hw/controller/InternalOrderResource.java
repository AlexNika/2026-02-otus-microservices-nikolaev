package ru.otus.hw.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.RefundRequestDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.service.AccountService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/order")
@Tag(name = "Internal Order API", description = "Internal REST API for order processing (service-to-service)")
public class InternalOrderResource {

    private final AccountService accountService;

    @Operation(summary = "Withdraw funds for order (internal)",
            description = "Withdraws funds from user's account for an order. Called by ORDERService during order processing.")
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawResponseDto> withdraw(@RequestBody WithdrawRequestDto request) {
        WithdrawResponseDto withdrawResponseDto = accountService.withdrawFromAccount(
                request.userId(),
                request.amount(),
                request.orderId()
        );
        return ResponseEntity.ok(withdrawResponseDto);
    }

    @Operation(summary = "Refund funds for cancelled order (internal)",
            description = "Refunds funds back to user's account for a cancelled order. Called by ORDERService when order is cancelled.")
    @PostMapping("/refund")
    public ResponseEntity<RefundResponseDto> refund(@RequestBody RefundRequestDto request) {
        RefundResponseDto refundResponseDto = accountService.refundToAccount(
                request.userId(),
                request.amount(),
                request.orderId()
        );
        return ResponseEntity.ok(refundResponseDto);
    }
}
