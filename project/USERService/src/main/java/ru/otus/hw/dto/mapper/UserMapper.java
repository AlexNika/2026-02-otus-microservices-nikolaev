package ru.otus.hw.dto.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.UserAddressResponseDto;
import ru.otus.hw.dto.UserProfileDto;
import ru.otus.hw.dto.UserProfileUpdateDto;
import ru.otus.hw.models.UserAddress;
import ru.otus.hw.models.UserProfile;

import java.util.List;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    @Mapping(target = "addresses", ignore = true)
    UserProfileDto toProfileDto(UserProfile profile);

    @Mapping(source = "profile.userName", target = "userName")
    @Mapping(source = "profile.firstName", target = "firstName")
    @Mapping(source = "profile.lastName", target = "lastName")
    @Mapping(source = "profile.birthdate", target = "birthdate")
    @Mapping(source = "profile.phone", target = "phone")
    @Mapping(source = "addresses", target = "addresses")
    UserProfileDto toProfileDto(UserProfile profile, List<UserAddress> addresses);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateProfileFromDto(UserProfileUpdateDto dto, @MappingTarget UserProfile profile);

    @Mapping(source = "id", target = "addressId")
    UserAddressResponseDto toAddressResponseDto(UserAddress address);

    List<UserAddressResponseDto> toAddressResponseList(List<UserAddress> addresses);
}
