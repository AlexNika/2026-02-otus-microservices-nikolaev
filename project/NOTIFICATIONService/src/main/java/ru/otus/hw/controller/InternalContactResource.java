package ru.otus.hw.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.hw.dto.ContactDto;
import ru.otus.hw.repository.NotificationContactRepository;

/**
 * Внутренний эндпоинт read-модели контактов (верификация асинхронной репликации
 * USER → NOTIFICATION и данные для будущих внутренних потребителей). Защищён общим
 * фильтром COMMONDomain по заголовку X-Internal-API-Key ({@code /internal/**}).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/contacts")
public class InternalContactResource {

    private final NotificationContactRepository contactRepository;

    @GetMapping("/user/{userId}")
    public ResponseEntity<ContactDto> getContacts(@PathVariable Long userId) {
        log.info("GET /internal/contacts/user/{}", userId);
        return contactRepository.findByUserId(userId)
                .map(contact -> new ContactDto(
                        contact.getUserId(),
                        contact.getEmail(),
                        contact.getPhone(),
                        contact.getUpdatedAt()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
