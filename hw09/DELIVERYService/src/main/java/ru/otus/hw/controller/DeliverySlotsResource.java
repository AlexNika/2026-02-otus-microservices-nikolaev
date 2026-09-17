package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.AvailableSlotsResponse;
import ru.otus.hw.service.DeliverySlotsService;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/delivery")
public class DeliverySlotsResource implements DeliverySlots {

    private final DeliverySlotsService deliverySlotsService;

    @Override
    public ResponseEntity<AvailableSlotsResponse> getAvailableSlots(LocalDate date) {
        return ResponseEntity.ok(deliverySlotsService.getAvailableSlots(date));
    }
}
