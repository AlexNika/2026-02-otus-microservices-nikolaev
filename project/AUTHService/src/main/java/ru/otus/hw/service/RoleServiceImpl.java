package ru.otus.hw.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.otus.hw.dto.RoleDto;
import ru.otus.hw.dto.mapper.RoleMapper;
import ru.otus.hw.models.Role;
import ru.otus.hw.repository.RoleRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;

    private final RoleMapper mapper;

    private final ObjectMapper objectMapper;

    @Override
    public List<RoleDto> getAll() {
        List<Role> roles = roleRepository.findAll();
        return roles.stream()
                .map(mapper::toRoleDto)
                .toList();
    }

    @Override
    public RoleDto getOne(Long id) {
        Optional<Role> roleOptional = roleRepository.findById(id);
        return mapper.toRoleDto(roleOptional.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Entity with id `%s` not found".formatted(id))));
    }

    @Override
    public RoleDto getOneByName(String name) {
        Optional<Role> roleOptional = roleRepository.findByName(name);
        return mapper.toRoleDto(roleOptional.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Entity with name `%s` not found".formatted(name))));
    }

    @Override
    public List<RoleDto> getMany(List<Long> ids) {
        List<Role> roles = roleRepository.findAllById(ids);
        return roles.stream()
                .map(mapper::toRoleDto)
                .toList();
    }

    @Override
    public RoleDto create(RoleDto dto) {
        Role role = mapper.toEntity(dto);
        Role resultRole = roleRepository.save(role);
        return mapper.toRoleDto(resultRole);
    }

    @Override
    public RoleDto patch(Long id, JsonNode patchNode) throws IOException {
        Role role = roleRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Entity with id `%s` not found".formatted(id)));

        RoleDto roleDto = mapper.toRoleDto(role);
        objectMapper.readerForUpdating(roleDto).readValue(patchNode);
        mapper.updateWithNull(roleDto, role);

        Role resultRole = roleRepository.save(role);
        return mapper.toRoleDto(resultRole);
    }

    @Override
    public List<Long> patchMany(List<Long> ids, JsonNode patchNode) throws IOException {
        Collection<Role> roles = roleRepository.findAllById(ids);

        for (Role role : roles) {
            RoleDto roleDto = mapper.toRoleDto(role);
            objectMapper.readerForUpdating(roleDto).readValue(patchNode);
            mapper.updateWithNull(roleDto, role);
        }

        List<Role> resultRoles = roleRepository.saveAll(roles);
        return resultRoles.stream()
                .map(Role::getId)
                .toList();
    }

    @Override
    public RoleDto delete(Long id) {
        Role role = roleRepository.findById(id).orElse(null);
        if (role != null) {
            roleRepository.delete(role);
        }
        return mapper.toRoleDto(role);
    }

    @Override
    public void deleteMany(List<Long> ids) {
        roleRepository.deleteAllById(ids);
    }

    @Override
    public void deleteAll() {
        roleRepository.deleteAll();
    }
}
