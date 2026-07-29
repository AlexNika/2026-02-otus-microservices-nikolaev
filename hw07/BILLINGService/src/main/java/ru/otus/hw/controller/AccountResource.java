package ru.otus.hw.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.service.AccountService;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/account")
public class AccountResource {

    private final AccountService accountService;

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponseDto> getAccountById(@PathVariable Long id) {
        AccountResponseDto accountResponseDto = accountService.getById(id);
        return ResponseEntity.ok(accountResponseDto);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<AccountResponseDto> getAccountByUserId(@PathVariable Long userId) {
        AccountResponseDto accountResponseDto = accountService.getByUserId(userId);
        return ResponseEntity.ok(accountResponseDto);
    }

    @PostMapping
    public ResponseEntity<AccountResponseDto> createAccount(@RequestBody AccountCreateDto accountCreateDto) {
        AccountResponseDto accountResponseDto = accountService.createAccount(accountCreateDto);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(accountResponseDto.id())
                .toUri();
        return ResponseEntity.created(location).body(accountResponseDto);
    }

    @PostMapping("/{userId}/deposit")
    public ResponseEntity<DepositResponseDto> deposit(@PathVariable Long userId,
                                                      @Valid @RequestBody DepositRequestDto depositRequestDto) {
        DepositResponseDto depositResponseDto = accountService.depositToAccount(userId, depositRequestDto.amount());
        return ResponseEntity.ok(depositResponseDto);
    }

    @PostMapping("/{userId}/withdraw")
    public ResponseEntity<WithdrawResponseDto> withdraw(@PathVariable Long userId,
                                                        @Valid @RequestBody WithdrawRequestDto request) {
        WithdrawResponseDto withdrawResponseDto = accountService.withdrawFromAccount(userId, request.amount(), request.orderId());
        return ResponseEntity.ok(withdrawResponseDto);
    }
}
