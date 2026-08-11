// Smoke-тест DELIVERYService (порт 8005).
// Сценарий: настройка ёмкости -> резервы -> исчерпание слота -> confirm/cancel -> admin page.
// Перед запуском очистить данные на завтрашнюю дату в БД (см. README ниже в useCase).
// Запуск: сервис поднят, Postgres 5437, ключ в connekt.env.json.

val host: String by env
val internalApiKey: String by env

// Завтрашняя дата и уникальные orderId (чтобы не пересекаться с прошлыми прогонами)
val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
val orderBase = System.currentTimeMillis()
val order1 = orderBase
val order2 = orderBase + 1
val order3 = orderBase + 2
val unknownOrder = orderBase + 999

data class CapacitySlot(val slotId: Long?, val slotStart: String, val slotEnd: String,
                        val capacity: Int?, val reservedCount: Int?)
data class Capacity(val capacityDate: String, val courierCount: Int, val slots: List<CapacitySlot>)
data class AvailableSlot(val slotStart: String, val slotEnd: String, val available: Boolean)
data class AvailableSlots(val date: String, val slots: List<AvailableSlot>)
data class Reservation(val reservationId: Long, val orderId: Long, val date: String,
                       val slotStart: String, val slotEnd: String,
                       val assignedCourierNumber: Int?, val status: String)
data class CancelResult(val orderId: Long, val result: String)
data class ErrorBody(val message: String?, val status: Int?, val code: String?)

fun RequestBuilder.withInternalKey() {
    header("X-Internal-API-Key", internalApiKey)
}

val smoke by useCase("DELIVERYService smoke") {

    // ============================== 1. Capacity setup ==============================

    val putCapacity by PUT("$host/api/v1/delivery/courier-capacity/{date}") {
        pathParam("date", tomorrow)
        contentType("application/json")
        body("""{"courierCount": 2}""")
    } then {
        assert(code == 200) { "PUT capacity expected 200 but got $code" }
        decode<Capacity>()
    }
    assert(putCapacity.courierCount == 2) { "courierCount must be 2" }
    assert(putCapacity.slots.size == 4) { "Expected 4 default slots but got ${putCapacity.slots.size}" }

    GET("$host/api/v1/delivery/courier-capacity/{date}") {
        pathParam("date", tomorrow)
    } then {
        assert(code == 200) { "GET capacity expected 200 but got $code" }
        val capacity = decode<Capacity>()
        assertSoftly {
            assert(capacity.capacityDate == tomorrow)
            assert(capacity.courierCount == 2)
            assert(capacity.slots.all { it.capacity == 2 })
            assert(capacity.slots.all { it.reservedCount == 0 })
        }
    }

    GET("$host/api/v1/delivery/slots") {
        queryParam("date", tomorrow)
    } then {
        assert(code == 200) { "GET slots expected 200 but got $code" }
        val available = decode<AvailableSlots>()
        assert(available.date == tomorrow)
        assert(available.slots.size == 4)
        assert(available.slots.all { it.available }) { "All slots must be available before reservations" }
    }

    // ============================== 2. Reserve + idempotency ==============================

    val order1Reservation by POST("$host/internal/delivery/reservations") {
        withInternalKey()
        contentType("application/json")
        body("""{"orderId": $order1, "date": "$tomorrow", "slotStart": "10:00", "slotEnd": "12:00"}""")
    } then {
        assert(code == 201) { "First reserve expected 201 but got $code" }
        decode<Reservation>()
    }
    assert(order1Reservation.orderId == order1)
    assert(order1Reservation.status == "RESERVED")
    assert(order1Reservation.slotStart == "10:00")

    POST("$host/internal/delivery/reservations") {
        withInternalKey()
        contentType("application/json")
        body("""{"orderId": $order1, "date": "$tomorrow", "slotStart": "10:00", "slotEnd": "12:00"}""")
    } then {
        assert(code == 200) { "Idempotent replay expected 200 but got $code" }
        val replayed = decode<Reservation>()
        assert(replayed.reservationId == order1Reservation.reservationId)
        assert(replayed.status == "RESERVED")
    }

    // ============================== 3. Fill slot -> 409 no free courier ==============================

    POST("$host/internal/delivery/reservations") {
        withInternalKey()
        contentType("application/json")
        body("""{"orderId": $order2, "date": "$tomorrow", "slotStart": "10:00", "slotEnd": "12:00"}""")
    } then {
        assert(code == 201) { "Second reserve expected 201 but got $code" }
        val second = decode<Reservation>()
        assert(second.assignedCourierNumber != null)
        assert(second.assignedCourierNumber != order1Reservation.assignedCourierNumber)
    }

    POST("$host/internal/delivery/reservations") {
        withInternalKey()
        contentType("application/json")
        body("""{"orderId": $order3, "date": "$tomorrow", "slotStart": "10:00", "slotEnd": "12:00"}""")
    } then {
        assert(code == 409) { "Third reserve expected 409 but got $code" }
        val error = decode<ErrorBody>()
        assert(error.code == "DELIVERY_NO_FREE_COURIER") { "Unexpected error code: ${error.code}" }
    }

    // ============================== 4. Internal GET endpoints + 401 ==============================

    GET("$host/internal/delivery/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", order1)
    } then {
        assert(code == 200) { "GET by orderId expected 200 but got $code" }
        val found = decode<Reservation>()
        assert(found.orderId == order1)
        assert(found.reservationId == order1Reservation.reservationId)
    }

    GET("$host/internal/delivery/reservations/by-id/{reservationId}") {
        withInternalKey()
        pathParam("reservationId", order1Reservation.reservationId)
    } then {
        assert(code == 200) { "GET by-id expected 200 but got $code" }
        assert(decode<Reservation>().orderId == order1)
    }

    GET("$host/internal/delivery/reservations/{orderId}") {
        pathParam("orderId", order1)
    } then {
        assert(code == 401) { "Request without API key expected 401 but got $code" }
    }

    // ============================== 5. Confirm flow ==============================

    POST("$host/internal/delivery/reservations/{orderId}/confirm") {
        withInternalKey()
        pathParam("orderId", order1)
    } then {
        assert(code == 200) { "Confirm expected 200 but got $code" }
        assert(decode<Reservation>().status == "CONFIRMED")
    }

    POST("$host/internal/delivery/reservations/{orderId}/confirm") {
        withInternalKey()
        pathParam("orderId", order1)
    } then {
        assert(code == 200) { "Repeated confirm expected 200 but got $code" }
        assert(decode<Reservation>().status == "CONFIRMED")
    }

    POST("$host/internal/delivery/reservations/{orderId}/cancel") {
        withInternalKey()
        pathParam("orderId", order1)
    } then {
        assert(code == 409) { "Cancel of CONFIRMED expected 409 but got $code" }
        val error = decode<ErrorBody>()
        assert(error.code == "DELIVERY_RESERVATION_STATE_CONFLICT") { "Unexpected error code: ${error.code}" }
    }

    // ============================== 6. Cancel flows ==============================

    POST("$host/internal/delivery/reservations/{orderId}/cancel") {
        withInternalKey()
        pathParam("orderId", order2)
    } then {
        assert(code == 200) { "Cancel expected 200 but got $code" }
        assert(decode<CancelResult>().result == "CANCELLED")
    }

    POST("$host/internal/delivery/reservations/{orderId}/cancel") {
        withInternalKey()
        pathParam("orderId", order2)
    } then {
        assert(code == 200) { "Repeated cancel expected 200 but got $code" }
        assert(decode<CancelResult>().result == "ALREADY_CANCELLED")
    }

    POST("$host/internal/delivery/reservations/{orderId}/cancel") {
        withInternalKey()
        pathParam("orderId", unknownOrder)
    } then {
        assert(code == 200) { "Cancel of unknown order expected 200 but got $code" }
        assert(decode<CancelResult>().result == "NOT_FOUND")
    }

    // ============================== 7. Admin reservations page ==============================

    GET("$host/api/v1/delivery/reservations") {
        queryParam("date", tomorrow)
        queryParam("status", "CONFIRMED")
    } then {
        assert(code == 200) { "Admin reservations page expected 200 but got $code" }
        val content = jsonPath().decode<List<Reservation>>("$.content")
        assert(content.any { it.orderId == order1 }) { "Confirmed reservation for order1 must be on the page" }
    }

    GET("$host/api/v1/delivery/orders/{orderId}/reservation") {
        pathParam("orderId", order1)
    } then {
        assert(code == 200) { "Admin get by orderId expected 200 but got $code" }
        assert(decode<Reservation>().status == "CONFIRMED")
    }

    order1Reservation
}
