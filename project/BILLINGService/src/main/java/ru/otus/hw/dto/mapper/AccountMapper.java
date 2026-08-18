package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.models.Account;
import ru.otus.hw.models.Transaction;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE, componentModel = MappingConstants.ComponentModel.SPRING)
public interface AccountMapper {
    Account toEntity(AccountResponseDto accountResponseDto);

    AccountResponseDto toAccountResponseDto(Account account);

    Account toEntity(AccountCreateDto accountCreateDto);

    AccountCreateDto toAccountCreateDto(Account account);

    @Mapping(source = "account.userId", target = "userId")
    @Mapping(source = "account.id", target = "accountId")
    @Mapping(source = "balanceAfter", target = "newBalance")
    @Mapping(source = "id", target = "transactionId")
    DepositResponseDto toDepositResponseDto(Transaction transaction);

    @Mapping(source = "account.userId", target = "userId")
    @Mapping(source = "account.id", target = "accountId")
    @Mapping(source = "balanceAfter", target = "newBalance")
    @Mapping(source = "id", target = "transactionId")
    @Mapping(target = "success", constant = "true")
    WithdrawResponseDto toWithdrawResponseDto(Transaction transaction);
}