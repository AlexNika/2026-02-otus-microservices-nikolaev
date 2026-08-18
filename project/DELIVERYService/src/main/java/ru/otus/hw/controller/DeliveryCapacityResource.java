package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.SetCourierCapacityRequest;
import ru.otus.hw.service.DeliveryCapacityService;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/delivery/courier-capacity")
public class DeliveryCapacityResource implements DeliveryCapacity {

    private final DeliveryCapacityService deliveryCapacityService;

    @Override
    public ResponseEntity<CapacityResponse> setCapacity(LocalDate date, SetCourierCapacityRequest request) {
        return ResponseEntity.ok(deliveryCapacityService.setCapacity(date, request));
    }

    @Override
    public ResponseEntity<CapacityResponse> getCapacity(LocalDate date) {
        return ResponseEntity.ok(deliveryCapacityService.getCapacity(date));
    }
}
