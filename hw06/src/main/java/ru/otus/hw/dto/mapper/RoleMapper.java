package ru.otus.hw.dto.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;
import ru.otus.hw.dto.RoleDto;
import ru.otus.hw.dto.UpdateRoleDto;
import ru.otus.hw.models.Role;

@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = MappingConstants.ComponentModel.SPRING)
public interface RoleMapper {
    Role toEntity(RoleDto roleDto);

    RoleDto toRoleDto(Role role);

    void updateRoleFromDto(UpdateRoleDto dto, @MappingTarget Role role);

    Role updateWithNull(RoleDto roleDto, @MappingTarget Role role);
}