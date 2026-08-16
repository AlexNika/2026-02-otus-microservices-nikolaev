package ru.otus.hw.config.properties;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "app.delivery")
public class CourierSlotProperties implements CourierSlotConfig {

    /**
     * Список дефолтных слотов в формате "HH:mm-HH:mm".
     * Например:
     * "10:00-12:00"
     */
    private List<String> defaultSlots = new ArrayList<>();

    /**
     * Проверяет корректность всех слотов из application.yaml.
     */
    @AssertTrue(message = "Each delivery.default-slots item must be in format HH:mm-HH:mm, and start must be before end")
    public boolean isDefaultSlotsValid() {
        if (defaultSlots == null || defaultSlots.isEmpty()) {
            return true;
        }

        return defaultSlots.stream().allMatch(this::isValidSlot);
    }

    /**
     * Возвращает распарсенные слоты.
     * Если список пустой, возвращает fallback-слоты.
     */
    public List<SlotInterval> getDefaultSlotIntervals() {
        if (defaultSlots == null || defaultSlots.isEmpty()) {
            return SlotInterval.DEFAULT_FALLBACK;
        }

        return defaultSlots.stream()
                .map(this::parseSlot)
                .toList();
    }

    private boolean isValidSlot(String rawSlot) {
        try {
            parseSlot(rawSlot);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private SlotInterval parseSlot(String rawSlot) {
        if (rawSlot == null || !rawSlot.contains("-")) {
            throw new IllegalArgumentException("Slot must be in format HH:mm-HH:mm");
        }
        String[] parts = rawSlot.split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Slot must be in format HH:mm-HH:mm");
        }

        LocalTime start;
        LocalTime end;

        try {
            start = LocalTime.parse(parts[0].trim());
            end = LocalTime.parse(parts[1].trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Slot time must be in format HH:mm, value: " + rawSlot, e);
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Slot start must be before slot end, value: " + rawSlot);
        }
        return new SlotInterval(start, end);
    }

    /**
     * Внутреннее представление слота.
     */
    public record SlotInterval(
            LocalTime start,
            LocalTime end
    ) {

        /**
         * Fallback-слоты на случай, если конфиг пустой.
         */
        public static final List<SlotInterval> DEFAULT_FALLBACK = List.of(
                new SlotInterval(LocalTime.of(10, 0), LocalTime.of(12, 0)),
                new SlotInterval(LocalTime.of(12, 0), LocalTime.of(14, 0)),
                new SlotInterval(LocalTime.of(14, 0), LocalTime.of(16, 0)),
                new SlotInterval(LocalTime.of(16, 0), LocalTime.of(18, 0))
        );
    }
}
