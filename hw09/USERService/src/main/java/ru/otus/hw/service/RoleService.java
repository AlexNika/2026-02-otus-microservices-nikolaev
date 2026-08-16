package ru.otus.hw.service;

import com.fasterxml.jackson.databind.JsonNode;
import ru.otus.hw.dto.RoleDto;

import java.io.IOException;
import java.util.List;

public interface RoleService {

    List<RoleDto> getAll();

    RoleDto getOne(Long id);

    RoleDto getOneByName(String name);

    List<RoleDto> getMany(List<Long> ids);

    RoleDto create(RoleDto dto);

    RoleDto patch(Long id, JsonNode patchNode) throws IOException;

    List<Long> patchMany(List<Long> ids, JsonNode patchNode) throws IOException;

    RoleDto delete(Long id);

    void deleteMany(List<Long> ids);

    void deleteAll();
}
