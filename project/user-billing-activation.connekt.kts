// USER→BILLING eventual-consistency smoke test against the local fp stack.
// Ports: USER 8000, BILLING 8001, NOTIFICATION 8003.
// Prereqs: DEV/docker-compose.yaml DBs + RabbitMQ up, USER/BILLING/NOTIFICATION services running
// locally with the vars from .env (registration must not require BILLINGService availability).
// Flow: register -> 201 accountStatus=PENDING -> poll accountStatus until ACTIVE
//       -> billing account exists -> NOTIFICATION records USER_CREATED / ACCOUNT_CREATED /
//       ACCOUNT_ACTIVATED (all with orderId=null).

val userHost: String by env
val billingHost: String by env
val notificationHost: String by env

val base = System.currentTimeMillis()

data class UserResponse(val id: Long?, val userName: String?, val firstName: String?,
                        val lastName: String?, val email: String?, val accountStatus: String?)

data class Account(val id: Long?, val userId: Long?, val balance: Double?,
                   val enabled: Boolean?, val locked: Boolean?)

data class Notification(val id: Long?, val userId: Long?, val message: String?, val orderId: Long?,
                        val notificationStatus: String?, val created: String?)

val registered by POST("$userHost/api/v1/auth/register") {
    contentType("application/json")
    body("""{"userName":"fp_async_$base","firstName":"Fp","lastName":"Async",
            |"email":"fp.async.$base@example.com","password":"Password1!"}""".trimMargin())
} then {
    assert(code == 201) { "Register expected 201 but got $code" }
    val user = decode<UserResponse>()
    assert(user.id != null) { "Register response has no user id" }
    assert(user.accountStatus == "PENDING") {
        "Right after registration accountStatus expected PENDING but was ${user.accountStatus}"
    }
    user
}

// The billing account is created asynchronously (outbox -> UserCreatedEvent -> BILLING consumer ->
// AccountCreatedEvent -> activation consumer). Poll the user until the status flips to ACTIVE.
val activated by useCase("Wait for async billing-account activation") {
    var status: String? = null
    var attempt = 0
    do {
        Thread.sleep(1000)
        status = GET("$userHost/api/v1/user/{userId}") {
            pathParam("userId", registered.id!!)
        } then {
            assert(code == 200) { "GET user expected 200 but got $code" }
            decode<UserResponse>().accountStatus
        }
        attempt++
    } while (status != "ACTIVE" && attempt < 20)
    assert(status == "ACTIVE") {
        "accountStatus did not become ACTIVE within 20 attempts (last: $status)"
    }
    status
}

GET("$billingHost/api/v1/account/user/{userId}") {
    pathParam("userId", registered.id!!)
} then {
    assert(code == 200) { "Billing account expected 200 but got $code" }
    val account = decode<Account>()
    assert(account.userId == registered.id) {
        "Billing account userId mismatch: expected ${registered.id}, got ${account.userId}"
    }
}

// Best-effort notifications: after activation all three lifecycle records must be present
// (orderId=null distinguishes them from order notifications). Poll briefly against the race
// between the ACTIVE flip and the ACCOUNT_ACTIVATED notification persisting.
val lifecycleNotifications by useCase("Wait for lifecycle notifications") {
    var events = listOf<Notification>()
    var ready = false
    var attempt = 0
    do {
        events = GET("$notificationHost/api/v1/notification") {
            queryParam("userId", registered.id!!)
        } then {
            assert(code == 200) { "Notifications expected 200 but got $code" }
            decode<List<Notification>>()
        }
        ready = events.any { it.notificationStatus == "USER_CREATED" && it.orderId == null }
                && events.any { it.notificationStatus == "ACCOUNT_CREATED" && it.orderId == null }
                && events.any { it.notificationStatus == "ACCOUNT_ACTIVATED" && it.orderId == null }
        if (!ready && attempt < 9) {
            Thread.sleep(1000)
        }
        attempt++
    } while (!ready && attempt < 10)

    val statuses = events.map { it.notificationStatus }
    assert(events.any { it.notificationStatus == "USER_CREATED" && it.orderId == null }) {
        "Expected USER_CREATED notification (orderId=null), got statuses: $statuses"
    }
    assert(events.any { it.notificationStatus == "ACCOUNT_CREATED" && it.orderId == null }) {
        "Expected ACCOUNT_CREATED notification (orderId=null), got statuses: $statuses"
    }
    assert(events.any { it.notificationStatus == "ACCOUNT_ACTIVATED" && it.orderId == null }) {
        "Expected ACCOUNT_ACTIVATED notification (orderId=null), got statuses: $statuses"
    }
    events
}
