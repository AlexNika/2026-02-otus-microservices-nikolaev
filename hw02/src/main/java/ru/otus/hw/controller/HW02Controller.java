package ru.otus.hw.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HW02Controller {

    @GetMapping("/health/")
    public Map<String, String> health() {
        return Map.of("status", "OK");
    }
}
