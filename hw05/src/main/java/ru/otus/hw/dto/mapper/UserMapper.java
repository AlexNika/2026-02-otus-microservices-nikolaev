package ru.otus.hw.dto.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserPatchDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.UserUpdateDto;
import ru.otus.hw.models.User;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {
    User toEntity(UserCreateDto userCreateDto);

    UserResponseDto toUserResponseDto(User user);

    void updateUserFromDto(UserUpdateDto dto, @MappingTarget User user);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void partialUpdateFromDto(UserPatchDto dto, @MappingTarget User user);
}