package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.DeliveryAddressViewDto;
import ru.otus.hw.repository.DeliveryUserAddressRepository;

import java.util.List;

/**
 * Внутренний эндпоинт read-модели адресов доставки (верификация асинхронной репликации
 * USER → DELIVERY и данные для будущего OrderService/саги). Защищён общим фильтром
 * COMMONDomain по заголовку X-Internal-API-Key ({@code /internal/**}).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/delivery/user-addresses")
public class InternalUserAddressResource {

    private final DeliveryUserAddressRepository addressRepository;

    @GetMapping("/{userId}")
    public ResponseEntity<List<DeliveryAddressViewDto>> getUserAddresses(@PathVariable Long userId) {
        log.info("GET /internal/delivery/user-addresses/{}", userId);
        List<DeliveryAddressViewDto> addresses = addressRepository.findAllByUserId(userId).stream()
                .map(address -> new DeliveryAddressViewDto(
                        address.getSourceAddressId(),
                        address.getFullAddress(),
                        address.getCity(),
                        address.getPostalCode(),
                        address.getIsDefault(),
                        address.getPreferences()))
                .toList();
        return ResponseEntity.ok(addresses);
    }
}
