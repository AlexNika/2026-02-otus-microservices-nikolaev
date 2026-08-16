// Order-saga smoke test against the local hw08 stack.
// Ports: USER 8000, BILLING 8001, ORDER 8002, NOTIFICATION 8003, WAREHOUSE 8004, DELIVERY 8005.
// Prereqs: DEV/docker-compose.yaml DBs + RabbitMQ up, all services running locally, keys in connekt.env.json.
// Flow: S1 success/confirm -> S2 billing fail -> S3 warehouse fail -> S4 delivery fail -> S5 cancel conflict -> notifications.

val userHost: String by env
val billingHost: String by env
val orderHost: String by env
val notificationHost: String by env
val warehouseHost: String by env
val deliveryHost: String by env
val internalApiKey: String by env

// Tomorrow's date (delivery date must be FutureOrPresent) and a unique run suffix.
val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
val base = System.currentTimeMillis()

// Anti-collision against stale delivery reservations left by previous runs: the probe below
// reads the actual slot occupancy for the delivery date and recalculates courierCount.
// S1 takes a free slot, S4a/S4b target the busiest slot (S4a fills its last place so S4b
// fails as expected), S2/S3 use the aux slot (they fail before the delivery step anyway).
var reservedMax = 0
// Default slots for the 404 case (no capacity configured for the date yet):
var s1SlotStart = "12:00"
var s1SlotEnd = "14:00"
var s4SlotStart = "14:00"
var s4SlotEnd = "16:00"
var auxSlotStart = "16:00"
var auxSlotEnd = "18:00"

data class Product(val id: Long?, val manufacturerArticle: String?, val sku: String?,
                   val name: String?, val description: String?, val price: Double?)

data class Stock(val id: Long?, val manufacturerArticle: String?, val sku: String?,
                 val name: String?, val description: String?, val price: Double?,
                 val availableQuantity: Int?, val reservedQuantity: Int?)

data class User(val id: Long?, val userName: String?, val firstName: String?,
                val lastName: String?, val email: String?)

data class Account(val id: Long?, val userId: Long?, val balance: Double?,
                   val enabled: Boolean?, val locked: Boolean?)

data class Deposit(val userId: Long?, val accountId: Long?, val newBalance: Double?,
                   val transactionId: Long?)

data class Order(val id: Long?, val userId: Long?, val price: Double?, val description: String?,
                 val productId: Long?, val quantity: Int?, val deliveryDate: String?,
                 val slotStart: String?, val slotEnd: String?, val orderStatus: String?)

data class ErrorBody(val message: String?, val status: Int?, val code: String?, val timestamp: String?)

data class ReservationItem(val id: Long?, val orderId: Long?, val productId: Long?, val sku: String?,
                           val quantity: Int?, val reservationStatus: String?, val idempotencyKey: String?,
                           val version: Long?, val created: String?, val updated: String?)

data class ReservationList(val orderId: Long?, val reservations: List<ReservationItem>?)

data class DeliveryReservation(val reservationId: Long?, val orderId: Long?, val date: String?,
                                val slotStart: String?, val slotEnd: String?,
                                val assignedCourierNumber: Int?, val status: String?)

data class CapacitySlot(val slotId: Long?, val slotStart: String?, val slotEnd: String?,
                        val capacity: Int?, val reservedCount: Int?)

data class CapacityResponse(val capacityDate: String?, val courierCount: Int?,
                            val slots: List<CapacitySlot>?)

data class CancelResult(val orderId: Long?, val result: String?)

data class Notification(val id: Long?, val userId: Long?, val message: String?, val orderId: Long?,
                        val notificationStatus: String?, val created: String?)

fun RequestBuilder.withInternalKey() {
    header("X-Internal-API-Key", internalApiKey)
}

fun userBody(tag: String) =
    """{"userName":"saga_${tag}_$base","firstName":"Saga","lastName":"$tag",
        |"email":"saga.$tag.$base@example.com","password":"Password1!"}""".trimMargin()

fun accountBody(userId: Long) = """{"userId":$userId}"""

fun depositBody() = """{"amount":100.00}"""

fun orderBody(userId: Long, productId: Long, quantity: Int, slotStart: String, slotEnd: String) =
    """{"userId":$userId,"price":10.0000,"description":"saga order $base",
        |"productId":$productId,"quantity":$quantity,
        |"deliveryDate":"$tomorrow","slotStart":"$slotStart","slotEnd":"$slotEnd"}""".trimMargin()

fun productBody(skuSuffix: String, available: Int) =
    """{"manufacturerArticle":"ART-$skuSuffix-$base","sku":"SKU-$skuSuffix-$base",
        |"name":"Saga product $skuSuffix","description":"saga","price":10.00,
        |"productStock":{"availableQuantity":$available,"reservedQuantity":0}}""".trimMargin()

data class SagaResult(val userS1Id: Long, val orderS1Id: Long,
                      val userS2Id: Long, val orderS2Id: Long,
                      val userS3Id: Long, val orderS3Id: Long,
                      val userS4aId: Long, val orderS4aId: Long,
                      val userS4bId: Long, val orderS4bId: Long)

// Probe: read the actual slot occupancy for the delivery date before setting the capacity.
// Stale RESERVED/CONFIRMED reservations from previous runs are never deleted, and delivery
// dates cycle tomorrow + (base % 28), so a repeated run can land on an already occupied date.
GET("$deliveryHost/api/v1/delivery/courier-capacity/{date}") {
    pathParam("date", tomorrow)
} then {
    assert(code == 200 || code == 404) { "Capacity probe expected 200 or 404 but got $code" }
    if (code == 200) {
        val slots = decode<CapacityResponse>().slots.orEmpty()
        assert(slots.isNotEmpty()) { "Capacity probe: date $tomorrow has capacity but no slots" }
        // S4a/S4b target the busiest slot: courierCount = reservedMax + 1 leaves it exactly
        // one free place, so S4a succeeds and S4b fails with DELIVERY_NO_FREE_COURIER.
        val busiest = slots.maxByOrNull { it.reservedCount ?: 0 }!!
        reservedMax = busiest.reservedCount ?: 0
        s4SlotStart = busiest.slotStart!!
        s4SlotEnd = busiest.slotEnd!!
        // S1 must not share the S4 slot: otherwise S1 would take its last free place
        // and S4a (not S4b) would fail.
        val others = slots.filter { it.slotId != busiest.slotId }
        assert(others.isNotEmpty()) {
            "Capacity probe: date $tomorrow needs at least 2 slots but has ${slots.size}"
        }
        val s1 = others.first()
        s1SlotStart = s1.slotStart!!
        s1SlotEnd = s1.slotEnd!!
        val aux = others.drop(1).firstOrNull() ?: s1
        auxSlotStart = aux.slotStart!!
        auxSlotEnd = aux.slotEnd!!
    }
    // 404: no capacity configured for the date yet — defaults stand, reservedMax stays 0.
}

val saga by useCase("ORDER saga smoke") {

    // ============================ 0. Capacity + products + users ============================

    PUT("$deliveryHost/api/v1/delivery/courier-capacity/{date}") {
        pathParam("date", tomorrow)
        contentType("application/json")
        body("""{"courierCount": ${reservedMax + 1}}""")
    } then {
        assert(code == 200) { "PUT capacity expected 200 but got $code" }
    }

    val productMain by POST("$warehouseHost/api/v1/products") {
        contentType("application/json")
        body(productBody("main", 20))
    } then {
        assert(code == 201) { "Create main product expected 201 but got $code" }
        decode<Product>()
    }
    val productMainId = productMain.id!!

    val productS3 by POST("$warehouseHost/api/v1/products") {
        contentType("application/json")
        body(productBody("s3", 2))
    } then {
        assert(code == 201) { "Create S3 product expected 201 but got $code" }
        decode<Product>()
    }
    val productS3Id = productS3.id!!

    val userS1 by POST("$userHost/api/v1/auth/register") {
        contentType("application/json")
        body(userBody("s1"))
    } then {
        assert(code == 201) { "Register userS1 expected 201 but got $code" }
        decode<User>()
    }
    val userS1Id = userS1.id!!

    val userS2 by POST("$userHost/api/v1/auth/register") {
        contentType("application/json")
        body(userBody("s2"))
    } then {
        assert(code == 201) { "Register userS2 expected 201 but got $code" }
        decode<User>()
    }
    val userS2Id = userS2.id!!

    val userS3 by POST("$userHost/api/v1/auth/register") {
        contentType("application/json")
        body(userBody("s3"))
    } then {
        assert(code == 201) { "Register userS3 expected 201 but got $code" }
        decode<User>()
    }
    val userS3Id = userS3.id!!

    val userS4a by POST("$userHost/api/v1/auth/register") {
        contentType("application/json")
        body(userBody("s4a"))
    } then {
        assert(code == 201) { "Register userS4a expected 201 but got $code" }
        decode<User>()
    }
    val userS4aId = userS4a.id!!

    val userS4b by POST("$userHost/api/v1/auth/register") {
        contentType("application/json")
        body(userBody("s4b"))
    } then {
        assert(code == 201) { "Register userS4b expected 201 but got $code" }
        decode<User>()
    }
    val userS4bId = userS4b.id!!

    // Accounts + deposits for everyone except userS2 (S2 must fail with no account).
    POST("$billingHost/internal/account") {
        withInternalKey()
        contentType("application/json")
        body(accountBody(userS1Id))
    } then { assert(code == 200) { "Create account userS1 expected 200 but got $code" } }

    POST("$billingHost/api/v1/account/{userId}/deposit") {
        pathParam("userId", userS1Id)
        contentType("application/json")
        body(depositBody())
    } then { assert(code == 200) { "Deposit userS1 expected 200 but got $code" } }

    POST("$billingHost/internal/account") {
        withInternalKey()
        contentType("application/json")
        body(accountBody(userS3Id))
    } then { assert(code == 200) { "Create account userS3 expected 200 but got $code" } }

    POST("$billingHost/api/v1/account/{userId}/deposit") {
        pathParam("userId", userS3Id)
        contentType("application/json")
        body(depositBody())
    } then { assert(code == 200) { "Deposit userS3 expected 200 but got $code" } }

    POST("$billingHost/internal/account") {
        withInternalKey()
        contentType("application/json")
        body(accountBody(userS4aId))
    } then { assert(code == 200) { "Create account userS4a expected 200 but got $code" } }

    POST("$billingHost/api/v1/account/{userId}/deposit") {
        pathParam("userId", userS4aId)
        contentType("application/json")
        body(depositBody())
    } then { assert(code == 200) { "Deposit userS4a expected 200 but got $code" } }

    POST("$billingHost/internal/account") {
        withInternalKey()
        contentType("application/json")
        body(accountBody(userS4bId))
    } then { assert(code == 200) { "Create account userS4b expected 200 but got $code" } }

    POST("$billingHost/api/v1/account/{userId}/deposit") {
        pathParam("userId", userS4bId)
        contentType("application/json")
        body(depositBody())
    } then { assert(code == 200) { "Deposit userS4b expected 200 but got $code" } }

    // ============================ S1. success -> PLACED + CONFIRMED ============================

    val orderS1 by POST("$orderHost/api/v1/order") {
        contentType("application/json")
        body(orderBody(userS1Id, productMainId, 1, s1SlotStart, s1SlotEnd))
    } then {
        assert(code == 201) { "S1 create order expected 201 but got $code" }
        decode<Order>()
    }
    assert(orderS1.orderStatus == "PLACED") { "S1 order expected PLACED but was ${orderS1.orderStatus}" }
    val orderS1Id = orderS1.id!!

    GET("$warehouseHost/internal/products/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS1Id)
    } then {
        assert(code == 200) { "S1 warehouse reservation expected 200 but got $code" }
        val list = decode<ReservationList>()
        assert(list.reservations.orEmpty().all { it.reservationStatus == "CONFIRMED" }) {
            "S1 warehouse reservations must all be CONFIRMED"
        }
    }

    GET("$deliveryHost/internal/delivery/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS1Id)
    } then {
        assert(code == 200) { "S1 delivery reservation expected 200 but got $code" }
        assert(decode<DeliveryReservation>().status == "CONFIRMED") { "S1 delivery must be CONFIRMED" }
    }

    // ============================ S2. billing fail (no account) -> FAILED ============================

    POST("$orderHost/api/v1/order") {
        contentType("application/json")
        body(orderBody(userS2Id, productMainId, 1, auxSlotStart, auxSlotEnd))
    } then {
        assert(code == 502) { "S2 create order expected 502 (billing error) but got $code" }
    }

    val s2Orders by GET("$orderHost/api/v1/order/user/{userId}") {
        pathParam("userId", userS2Id)
    } then {
        assert(code == 200) { "S2 get orders expected 200 but got $code" }
        decode<List<Order>>()
    }
    val orderS2 = s2Orders.firstOrNull { it.orderStatus == "FAILED" }
    assert(orderS2 != null) { "S2 expected a FAILED order for userS2" }
    val orderS2Id = orderS2!!.id!!

    GET("$warehouseHost/internal/products/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS2Id)
    } then {
        assert(code == 404) { "S2 expected no warehouse reservation (404) but got $code" }
    }

    GET("$deliveryHost/internal/delivery/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS2Id)
    } then {
        assert(code == 404) { "S2 expected no delivery reservation (404) but got $code" }
    }

    // ============================ S3. warehouse fail (insufficient stock) -> FAILED + refund ============================

    POST("$orderHost/api/v1/order") {
        contentType("application/json")
        body(orderBody(userS3Id, productS3Id, 5, auxSlotStart, auxSlotEnd))
    } then {
        assert(code == 409) { "S3 create order expected 409 but got $code" }
        val err = decode<ErrorBody>()
        assert(err.code == "INSUFFICIENT_STOCK") { "S3 expected INSUFFICIENT_STOCK but got ${err.code}" }
    }

    val s3Orders by GET("$orderHost/api/v1/order/user/{userId}") {
        pathParam("userId", userS3Id)
    } then {
        assert(code == 200) { "S3 get orders expected 200 but got $code" }
        decode<List<Order>>()
    }
    val orderS3 = s3Orders.firstOrNull { it.orderStatus == "FAILED" }
    assert(orderS3 != null) { "S3 expected a FAILED order for userS3" }
    val orderS3Id = orderS3!!.id!!

    // refund must have returned the deposited money.
    GET("$billingHost/api/v1/account/user/{userId}") {
        pathParam("userId", userS3Id)
    } then {
        assert(code == 200) { "S3 get balance expected 200 but got $code" }
        assert(decode<Account>().balance == 100.0) { "S3 balance must be refunded to 100.00" }
    }

    GET("$deliveryHost/internal/delivery/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS3Id)
    } then {
        assert(code == 404) { "S3 expected no delivery reservation (404) but got $code" }
    }

    // ============================ S4. delivery fail (slot filled) -> FAILED + refund + RELEASED ============================

    val orderS4a by POST("$orderHost/api/v1/order") {
        contentType("application/json")
        body(orderBody(userS4aId, productMainId, 1, s4SlotStart, s4SlotEnd))
    } then {
        assert(code == 201) { "S4a create order expected 201 but got $code" }
        decode<Order>()
    }
    assert(orderS4a.orderStatus == "PLACED") { "S4a order expected PLACED but was ${orderS4a.orderStatus}" }
    val orderS4aId = orderS4a.id!!

    val stockBefore by GET("$warehouseHost/api/v1/products/{productId}/stocks") {
        pathParam("productId", productMainId)
    } then {
        assert(code == 200) { "S4 stock snapshot expected 200 but got $code" }
        decode<Stock>()
    }
    val availableBefore = stockBefore.availableQuantity!!

    POST("$orderHost/api/v1/order") {
        contentType("application/json")
        body(orderBody(userS4bId, productMainId, 1, s4SlotStart, s4SlotEnd))
    } then {
        assert(code == 409) { "S4b create order expected 409 but got $code" }
        val err = decode<ErrorBody>()
        assert(err.code == "DELIVERY_NO_FREE_COURIER") { "S4b expected DELIVERY_NO_FREE_COURIER but got ${err.code}" }
    }

    val s4bOrders by GET("$orderHost/api/v1/order/user/{userId}") {
        pathParam("userId", userS4bId)
    } then {
        assert(code == 200) { "S4b get orders expected 200 but got $code" }
        decode<List<Order>>()
    }
    val orderS4b = s4bOrders.firstOrNull { it.orderStatus == "FAILED" }
    assert(orderS4b != null) { "S4b expected a FAILED order for userS4b" }
    val orderS4bId = orderS4b!!.id!!

    GET("$billingHost/api/v1/account/user/{userId}") {
        pathParam("userId", userS4bId)
    } then {
        assert(code == 200) { "S4b get balance expected 200 but got $code" }
        assert(decode<Account>().balance == 100.0) { "S4b balance must be refunded to 100.00" }
    }

    GET("$warehouseHost/internal/products/reservations/{orderId}") {
        withInternalKey()
        pathParam("orderId", orderS4bId)
    } then {
        assert(code == 200) { "S4b warehouse reservation expected 200 but got $code" }
        val list = decode<ReservationList>()
        assert(list.reservations.orEmpty().all { it.reservationStatus == "RELEASED" }) {
            "S4b warehouse reservations must be RELEASED after compensation"
        }
    }

    GET("$warehouseHost/api/v1/products/{productId}/stocks") {
        pathParam("productId", productMainId)
    } then {
        assert(code == 200) { "S4 stock recheck expected 200 but got $code" }
        assert(decode<Stock>().availableQuantity == availableBefore) {
            "S4 available quantity must be restored after compensation"
        }
    }

    // ============================ S5. cancel a CONFIRMED order -> conflict, stays PLACED ============================

    POST("$orderHost/api/v1/order/{orderId}/cancel") {
        pathParam("orderId", orderS1Id)
    } then {
        assert(code == 409) { "S5 cancel CONFIRMED order expected 409 but got $code" }
        val err = decode<ErrorBody>()
        assert(err.code == "ORDER_STATE_CONFLICT") { "S5 expected ORDER_STATE_CONFLICT but got ${err.code}" }
    }

    GET("$orderHost/api/v1/order/{orderId}") {
        pathParam("orderId", orderS1Id)
    } then {
        assert(code == 200) { "S5 get order expected 200 but got $code" }
        assert(decode<Order>().orderStatus == "PLACED") { "S5 order must stay PLACED after failed cancel" }
    }

    SagaResult(userS1Id, orderS1Id, userS2Id, orderS2Id, userS3Id, orderS3Id,
            userS4aId, orderS4aId, userS4bId, orderS4bId)
}

// ============================ Notifications (async via RabbitMQ) ============================
// A small wait so the NOTIFICATIONService consumer can persist the events before we read them.
GET("$deliveryHost/api/v1/delivery/courier-capacity/{date}") {
    pathParam("date", tomorrow)
} then {
    Thread.sleep(3000)
    assert(code == 200)
}

GET("$notificationHost/api/v1/notification") {
    queryParam("userId", saga.userS1Id)
} then {
    assert(code == 200) { "Notifications S1 expected 200 but got $code" }
    val events = decode<List<Notification>>()
    assert(events.any { it.orderId == saga.orderS1Id && it.notificationStatus == "SUCCESS" }) {
        "Expected SUCCESS notification for S1 order ${saga.orderS1Id}"
    }
}

GET("$notificationHost/api/v1/notification") {
    queryParam("userId", saga.userS2Id)
} then {
    assert(code == 200) { "Notifications S2 expected 200 but got $code" }
    val events = decode<List<Notification>>()
    assert(events.any { it.orderId == saga.orderS2Id && it.notificationStatus == "FAILED" }) {
        "Expected FAILED notification for S2 order ${saga.orderS2Id}"
    }
}

GET("$notificationHost/api/v1/notification") {
    queryParam("userId", saga.userS3Id)
} then {
    assert(code == 200) { "Notifications S3 expected 200 but got $code" }
    val events = decode<List<Notification>>()
    assert(events.any { it.orderId == saga.orderS3Id && it.notificationStatus == "FAILED" }) {
        "Expected FAILED notification for S3 order ${saga.orderS3Id}"
    }
}

GET("$notificationHost/api/v1/notification") {
    queryParam("userId", saga.userS4bId)
} then {
    assert(code == 200) { "Notifications S4b expected 200 but got $code" }
    val events = decode<List<Notification>>()
    assert(events.any { it.orderId == saga.orderS4bId && it.notificationStatus == "FAILED" }) {
        "Expected FAILED notification for S4b order ${saga.orderS4bId}"
    }
}
