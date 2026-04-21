package ru.otus.hw.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
public class HW03Controller {

    @GetMapping({"/health", "/health/"})
    public Map<String, String> health() {
        log.debug("HW03Controller.health()");
        return Map.of("status", "OK");
    }
}
