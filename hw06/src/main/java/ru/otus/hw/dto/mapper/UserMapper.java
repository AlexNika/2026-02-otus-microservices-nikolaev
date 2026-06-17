package ru.otus.hw.dto.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.UserCreateDto;
import ru.otus.hw.dto.UserPatchDto;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.dto.UserResponseDto;
import ru.otus.hw.dto.UserUpdateDto;
import ru.otus.hw.models.User;
import ru.otus.hw.models.UserProfile;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {
    
    @Mapping(source = "userName", target = "profile.userName")
    @Mapping(source = "firstName", target = "profile.firstName")
    @Mapping(source = "lastName", target = "profile.lastName")
    User toEntity(UserCreateDto userCreateDto);

    @Mapping(source = "profile.userName", target = "userName")
    @Mapping(source = "profile.firstName", target = "firstName")
    @Mapping(source = "profile.lastName", target = "lastName")
    UserResponseDto toUserResponseDto(User user);

    @Mapping(source = "userName", target = "profile.userName")
    @Mapping(source = "firstName", target = "profile.firstName")
    @Mapping(source = "lastName", target = "profile.lastName")
    void updateUserFromDto(UserUpdateDto dto, @MappingTarget User user);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(source = "userName", target = "profile.userName")
    @Mapping(source = "firstName", target = "profile.firstName")
    @Mapping(source = "lastName", target = "profile.lastName")
    void partialUpdateFromDto(UserPatchDto dto, @MappingTarget User user);

    UserProfileDto toProfileDto(UserProfile profile);
    
    @Mapping(source = "userName", target = "userName")
    UserProfile toProfileEntity(UserProfileDto dto);
    
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateProfileFromDto(UserProfileUpdateDto dto, @MappingTarget UserProfile profile);
}