package ru.otus.hw.config.properties;

import java.util.List;

public interface CourierSlotConfig {

    List<CourierSlotProperties.SlotInterval> getDefaultSlotIntervals();
}
