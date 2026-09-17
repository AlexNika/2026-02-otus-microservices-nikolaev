package ru.otus.hw.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.otus.hw.models.Order;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бизнес-метрики заказов: терминальные статусы и выручка (приближение по double).
 */
class OrderBusinessMetricsTest {

    private SimpleMeterRegistry registry;

    private OrderBusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrderBusinessMetrics(registry);
    }

    private double counter(String name, String orderStatus) {
        Counter counter = registry.find(name).tags("order_status", orderStatus).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("PLACED: счётчик терминала + выручка на сумму заказа")
    void shouldCountPlacedOrderAndRevenue() {
        metrics.orderTerminal(Order.OrderStatus.PLACED, new BigDecimal("250.50"));

        assertThat(counter("orders.terminal", "PLACED")).isEqualTo(1.0);
        assertThat(registry.find("orders.revenue").counter().count()).isEqualTo(250.50);
    }

    @Test
    @DisplayName("FAILED: счётчик терминала без выручки")
    void shouldCountFailedOrderWithoutRevenue() {
        metrics.orderTerminal(Order.OrderStatus.FAILED, new BigDecimal("100.00"));

        assertThat(counter("orders.terminal", "FAILED")).isEqualTo(1.0);
        assertThat(registry.find("orders.revenue").counter().count()).isZero();
    }

    @Test
    @DisplayName("CANCELED: счётчик терминала без выручки")
    void shouldCountCanceledOrderWithoutRevenue() {
        metrics.orderTerminal(Order.OrderStatus.CANCELED, null);

        assertThat(counter("orders.terminal", "CANCELED")).isEqualTo(1.0);
        assertThat(registry.find("orders.revenue").counter().count()).isZero();
    }
}
