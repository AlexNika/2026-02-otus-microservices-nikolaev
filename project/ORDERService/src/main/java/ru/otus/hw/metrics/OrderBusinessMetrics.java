package ru.otus.hw.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Бизнес-показатели заказов: терминальные статусы и выручка.
 *
 * <p>Инкремент выполняется в единых точках фиксации заказа:
 * {@code finalize*WithNotification} (синхронный и recovery-пути саги) и
 * {@code cancelOrder}. Выручка ({@code orders.revenue}) - приближение:
 * {@link BigDecimal} приводится к {@code double}, для дашборда достаточно,
 * для бухгалтерского учёта - нет.
 */
@Slf4j
@Component
public class OrderBusinessMetrics {

    private static final String METRIC_TERMINAL = "orders.terminal";

    private static final String METRIC_REVENUE = "orders.revenue";

    private static final Order.OrderStatus[] TRACKED_STATUSES = {
            Order.OrderStatus.PLACED,
            Order.OrderStatus.FAILED,
            Order.OrderStatus.CANCELED};

    private final Map<Order.OrderStatus, Counter> terminalCounters = new EnumMap<>(Order.OrderStatus.class);

    private final Counter revenueCounter;

    public OrderBusinessMetrics(@NonNull MeterRegistry meterRegistry) {
        for (Order.OrderStatus status : TRACKED_STATUSES) {
            terminalCounters.put(status, Counter.builder(METRIC_TERMINAL)
                    .description("Orders reaching terminal status")
                    .tag("order_status", status.name())
                    .register(meterRegistry));
        }
        this.revenueCounter = Counter.builder(METRIC_REVENUE)
                .description("Placed orders revenue (approximation, double)")
                .register(meterRegistry);
    }

    public void orderTerminal(Order.@NonNull OrderStatus status, @Nullable BigDecimal price) {
        terminalCounters.get(status).increment();
        if (status == Order.OrderStatus.PLACED && price != null) {
            revenueCounter.increment(price.doubleValue());
        }
    }
}
