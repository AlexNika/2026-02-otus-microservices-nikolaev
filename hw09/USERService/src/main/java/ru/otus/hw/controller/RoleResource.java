package ru.otus.hw.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.RoleDto;
import ru.otus.hw.service.RoleService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasRole('ADMIN')")
public class RoleResource {

    private final RoleService roleService;

    @GetMapping
    public List<RoleDto> getAllRoles() {
        return roleService.getAll();
    }

    @GetMapping("/{id}")
    public RoleDto getRoleById(@PathVariable Long id) {
        return roleService.getOne(id);
    }

    @GetMapping("/{name}")
    public RoleDto getRoleByName(@PathVariable String name) {
        return roleService.getOneByName(name);
    }

    @GetMapping("/by-ids")
    public List<RoleDto> getManyRoles(@RequestParam List<Long> ids) {
        return roleService.getMany(ids);
    }

    @PostMapping
    public RoleDto create(@RequestBody RoleDto dto) {
        return roleService.create(dto);
    }

    @PatchMapping("/{id}")
    public RoleDto patch(@PathVariable Long id, @RequestBody JsonNode patchNode) throws IOException {
        return roleService.patch(id, patchNode);
    }

    @PatchMapping
    public List<Long> patchMany(@RequestParam List<Long> ids, @RequestBody JsonNode patchNode) throws IOException {
        return roleService.patchMany(ids, patchNode);
    }

    @DeleteMapping("/{id}")
    public RoleDto delete(@PathVariable Long id) {
        return roleService.delete(id);
    }

    @DeleteMapping
    public void deleteMany(@RequestParam List<Long> ids) {
        roleService.deleteMany(ids);
    }

    @DeleteMapping("/all")
    public void deleteAll() {
        roleService.deleteAll();
    }
}
