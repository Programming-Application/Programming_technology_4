https://chatgpt.com/share/69f04d6e-65bc-83e8-990a-76624e32389e

### ユーザー情報

- 端末側

```kotlin
data class User(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "USER", // USER / ADMIN
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)
```

### チケット情報

```kotlin
data class Ticket(
    val ticketId: String = "",
    val orderId: String = "",
    val screeningId: String = "",
    val movieId: String = "",
    val screenId: String = "",
    val seatId: String = "",
    val userId: String = "",
    val price: Int = 0,
    val status: String = "ACTIVE",
    val purchasedAt: Timestamp? = null,
    val canceledAt: Timestamp? = null,
    val usedAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
```

### 映画情報

```kotlin
data class Movie(
    val movieId: String = "",
    val title: String = "",
    val description: String = "",
    val durationMinutes: Int = 0,
    val isPublished: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
```

### 座席情報

```kotlin
screens/{screenId}/seats/{seatId}
- seatId: String         // A-10 など
- screenId: String
- row: String
- number: Int
- seatType: String       // NORMAL / PREMIUM / WHEELCHAIR
- isActive: Boolean
```

### 部屋情報

```kotlin
screens/{screenId}

- screenId: String
- name: String // Screen 1, Screen 2
- totalSeats: Int
- createdAt: Timestamp
- updatedAt: Timestamp
```

### 上映会情報

```kotlin
data class Screening(
    val screeningId: String = "",
    val movieId: String = "",
    val screenId: String = "",
    val startTime: Timestamp? = null,
    val endTime: Timestamp? = null,
    val salesStartAt: Timestamp? = null,
    val salesEndAt: Timestamp? = null,
    val status: String = "SCHEDULED",
    val isPrivate: Boolean = false,
    val availableSeatCount: Int = 0,
    val reservedSeatCount: Int = 0,
    val soldSeatCount: Int = 0,
    val lastUpdated: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
```

### 予約情報

```kotlin
data class Reservation(
    val reservationId: String = "",
    val userId: String = "",
    val screeningId: String = "",
    val seatIds: List<String> = emptyList(),
    val status: String = "HOLD",
    val expiresAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
```

### 購入情報

```kotlin
data class Order(
    val orderId: String = "",
    val userId: String = "",
    val screeningId: String = "",
    val reservationId: String = "",
    val ticketIds: List<String> = emptyList(),
    val totalAmount: Int = 0,
    val paymentStatus: String = "PENDING",
    val orderStatus: String = "CREATED",
    val purchasedAt: Timestamp? = null,
    val canceledAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)
```

### 上映会ごとの座席情報

```kotlin
screenings/{screeningId}/seatStates/{seatId}

data class SeatState(
    val seatId: String = "",
    val screeningId: String = "",
    val status: String = "AVAILABLE", // AVAILABLE / HOLD / SOLD / BLOCKED
    val reservationId: String? = null,
    val holdExpiresAt: Timestamp? = null,
    val ticketId: String? = null,
    val price: Int = 0,
    val updatedAt: Timestamp? = null,
)
```

## 最終的な構造

```jsx
users/{userId}

movies/{movieId}

screens/{screenId}
screens/{screenId}/seats/{seatId}

screenings/{screeningId}
screenings/{screeningId}/seatStates/{seatId}

reservations/{reservationId}

orders/{orderId}

tickets/{ticketId}
```