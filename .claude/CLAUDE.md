# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Spring Boot 3.3.5 ticket reservation system implementing distributed locking with Redisson and Redis-based seat state caching. The system handles high-concurrency ticket booking scenarios using the Facade pattern to coordinate distributed locks, Redis preemptive checks, and transactional database operations.

**Tech Stack:**
- Java 17 + Spring Boot 3.3.5
- MySQL 8.0 (primary data store)
- Redis + Redisson (distributed locks & state caching)
- Kafka (message broker)
- Spring Data JPA + Hibernate
- Lombok
- Actuator + Prometheus (monitoring)

## Build and Run Commands

### Start Infrastructure (Required First)
```bash
# Start MySQL, Redis, Kafka, Zookeeper via Docker Compose
docker-compose up -d

# Stop infrastructure
docker-compose down
```

### Build and Run Application
```bash
# Build project
./gradlew build

# Run application
./gradlew bootRun

# Build without tests
./gradlew build -x test

# Clean build
./gradlew clean build
```

### Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests com.example.ticket.TicketApplicationTests

# Run tests with detailed output
./gradlew test --info
```

### Other Useful Commands
```bash
# Check dependencies
./gradlew dependencies

# Refresh dependencies
./gradlew build --refresh-dependencies
```

## Architecture and Design Patterns

### Layered Architecture

The codebase follows a strict layered architecture with clear separation of concerns:

```
interfaces/          # REST controllers - entry point for HTTP requests
application/         # Facades - orchestrate cross-cutting concerns (locks, cache, transactions)
domain/             # Business entities and services - core business logic
  ├── reservation/  # Reservation aggregate
  ├── seat/         # Seat aggregate
  └── event/        # Domain events (ReservationEvent)
infrastructure/     # External system integrations
  └── kafka/        # Kafka producers and consumers
config/             # Spring configuration beans (Redis, Redisson, Kafka)
```

### Key Architectural Patterns

**1. Facade Pattern (Application Layer)**
- `ReservationFacade` orchestrates distributed lock acquisition, Redis preemptive checks, and domain service calls
- Separates infrastructure concerns (Redisson, RedisTemplate) from pure domain logic
- Flow: Lock → Redis State Check → Domain Service → Update Redis State

**2. Rich Domain Model**
- Business logic lives in entity methods (e.g., `Seat.reserve()`)
- Services coordinate entities but don't contain business rules
- Example: `Seat.reserve()` validates state and throws exception if already reserved

**3. Distributed Concurrency Control**
- **Redisson Distributed Lock**: Prevents race conditions across instances (`lock:seat:{seatId}`)
- **Redis Cache (optional)**: `state:seat:{seatId}` is a best-effort cache — **DB is the source of truth**. Cache failures are swallowed; DB state is always re-read after lock acquisition
- **TTL Strategy**: Redis state expires after 5 minutes to prevent stale data

**4. Real-Time Seat Status (SSE + Redis Pub/Sub)**
- `SeatStatusPublisher` publishes JSON messages to the `seat-status` Redis channel after every state change
- `SeatStatusSubscriber` receives channel messages and broadcasts them to all connected SSE clients via `SseEmitterRegistry`
- `SseEmitterRegistry` maintains a `ConcurrentHashMap` of active `SseEmitter` instances; emitters self-remove on completion/timeout/error
- Clients subscribe at `GET /api/v1/sse/seats` (no auth required)

**5. Event-Driven Architecture (Kafka)**
- **Asynchronous Event Publishing**: Reservation success/failure events published to Kafka
- **Topic**: `reservation-events`
- **Partitioning Strategy**: Same seat goes to same partition (key = seatId)
- **Consumer Group**: `ticket-reservation-group` with manual commit for reliability
- **Event Types**:
  - `RESERVATION_SUCCESS`: Published after successful DB commit
  - `RESERVATION_FAILED`: Published on lock timeout or duplicate reservation
  - `RESERVATION_CANCELLED`: Published when reservation is cancelled
- **Use Cases**:
  - User notifications (email, SMS, push)
  - Real-time statistics aggregation
  - Analytics and data warehousing
  - Audit logging

**6. Transactional Event Listener Pattern**
- `PaymentFacade.handleCallback()` publishes a Spring `PaymentResultEvent` (via `ApplicationEventPublisher`) inside `@Transactional`
- `PaymentEventListener` is annotated with `@TransactionalEventListener(phase = AFTER_COMMIT)` — Kafka publish, SSE broadcast, and queue removal happen **only after DB commits successfully**
- This prevents ghost events: if the transaction rolls back, the listener is never invoked

### Two-Phase Reservation Flow

**Phase 1 — Seat Hold (`ReservationFacade.reserve()`)**
1. Queue check: reject if user is not active in `WaitingQueueService`
2. Acquire Redisson lock (`lock:seat:{seatId}`, 1s wait / 2s hold)
3. Re-read seat from DB; reject if `SELECTED` or `CONFIRMED`
4. `ReservationService.hold()`: `Seat` → `SELECTED`, `Reservation` → `HELD`; returns `reservationId`
5. Update Redis cache (best-effort, errors swallowed)
6. Broadcast `SELECTED` via Redis Pub/Sub → SSE
7. Release lock in `finally`

**Phase 2 — Payment (`PaymentFacade.requestPayment()`)**
1. Verify reservation is `HELD`
2. Call external PG (WebClient, blocking `.block()`)
3. Save `Payment` as `PENDING`; return `transactionKey`

**Phase 3 — PG Callback (`PaymentFacade.handleCallback()`)**
- Runs inside `@Transactional`
- **SUCCESS (DB)**: `Payment.success()`, `Reservation` → `CONFIRMED`, `Seat` → `CONFIRMED`, update Redis cache
- **FAILURE (DB)**: `Payment.fail()`, `Reservation.cancel()`, `Seat` → `AVAILABLE`, delete Redis cache
- **After commit (`PaymentEventListener`)**: Kafka publish + queue removal + SSE broadcast (via `@TransactionalEventListener(AFTER_COMMIT)`)

### Status Enums

- `SeatStatus`: `AVAILABLE` → `SELECTED` → `CONFIRMED` (or back to `AVAILABLE` on failure)
- `ReservationStatus`: `HELD` → `CONFIRMED` | `CANCELLED`
- `PaymentStatus`: `PENDING` → `SUCCESS` | `FAILED`

### Domain Model Conventions

**Service Layer:**
- Default: `@Transactional(readOnly = true)` at class level
- CUD operations: Override with `@Transactional` on method

**Entity Layer:**
- Use domain methods for state transitions (not setters)
- Throw domain exceptions (e.g., `IllegalStateException`) for business rule violations
- Example: `seat.reserve()` instead of `seat.setReserved(true)`

## Authentication

JWT-based stateless auth with Spring Security:

- `UserService.signup()` encodes password with `BCryptPasswordEncoder` and persists a `User` entity
- `AuthController` issues a JWT on successful login via `JwtTokenProvider.generateAccessToken()`
- `JwtAuthenticationFilter` validates Bearer tokens on every request and populates `SecurityContextHolder`
- `PasswordEncoderConfig` is a separate `@Configuration` to avoid circular dependency between `SecurityConfig` and `UserService`

**Public endpoints** (no JWT required): `/api/v1/auth/**`, `/api/v1/payments/callback`, `/api/v1/sse/**`, `/actuator/**`

**CORS**: only `http://localhost:5173` (frontend dev server) is allowed

## Configuration Files

**application.properties** - Contains:
- MySQL connection details (localhost:3306/ticket_db)
- Redis connection (localhost:6379)
- Kafka bootstrap servers (localhost:9092)
- JPA/Hibernate settings (DDL auto-update, SQL logging)

**docker-compose.yml** - Defines:
- MySQL 8.0 on port 3306
- Redis on port 6379
- Kafka + Zookeeper for messaging

**RedissonConfig.java** - Configures Redisson client for distributed locking using single-server mode

**RedisConfig.java** - Configures `RedisTemplate<String, String>` and `RedisMessageListenerContainer` for Pub/Sub (`SeatStatusSubscriber` listens on `seat-status` channel)

**WebClientConfig.java** - Configures `pgWebClient` bean pointing to the external PG server (base URL from `payment.pg.base-url` property)

**KafkaConfig.java** - Configures Kafka producer and consumer:
- Producer: `acks=all` (all replicas acknowledge), 3 retries
- Consumer: Manual commit mode (`MANUAL_IMMEDIATE`) for reliability
- Consumer group: `ticket-reservation-group`
- Offset reset: `earliest` (start from beginning if no offset)

## Security and Code Quality Requirements

### From Global Rules (Enforced)

**Rich Domain Model:**
- Business logic must be implemented in entity methods, not services
- Services only handle transaction boundaries and flow control
- Never use setters for state changes - use domain methods

**Transaction Management:**
- Class-level `@Transactional(readOnly = true)` by default
- Override with `@Transactional` only on CUD methods

**JPA Optimization:**
- Use `fetch join` or `@EntityGraph` to prevent N+1 queries
- Currently no explicit relationships defined, but apply this when adding associations

**No Hardcoding:**
- Never hardcode roles, secrets, or config values
- Use Enums or `@ConfigurationProperties` classes

**SQL Injection Prevention:**
- Use Spring Data JPA or QueryDSL exclusively
- If using native queries, verify parameter binding

**Validation:**
- All controller DTOs must use `@Valid` for input validation
- DTO fields must declare `@NotNull` / `@NotBlank` / `@Positive` constraints — `@Valid` alone does nothing without field-level annotations

**Redis:**
- Never use `redisTemplate.keys(pattern)` in production code — it is O(N) and blocks the entire Redis event loop
- Use `redisTemplate.scan(ScanOptions)` instead (iterates in chunks of ~100, non-blocking)

## Key Implementation Files

### Event-Driven Components

**ReservationEvent** (`domain/event/ReservationEvent.java`)
- Domain event DTO with factory methods: `success()`, `failed()`
- Contains: reservationId, userId, seatId, seatNumber, timestamp, eventType
- `toJson()` method for Kafka serialization

**ReservationEventProducer** (`infrastructure/kafka/ReservationEventProducer.java`)
- Publishes events to `reservation-events` topic
- Async publishing with completion callbacks for logging
- `publishSync()` available for critical events requiring confirmation

**ReservationEventConsumer** (`infrastructure/kafka/ReservationEventConsumer.java`)
- Listens to `reservation-events` topic
- Manual commit for at-least-once delivery guarantee
- Processes events by type: `RESERVATION_SUCCESS`, `RESERVATION_FAILED`, `RESERVATION_CANCELLED`

**NotificationConsumer** (`infrastructure/kafka/NotificationConsumer.java`)
- Separate consumer for notification-specific handling on the same topic

### Kafka Integration Points

- `ReservationFacade`: lock timeout or exception → `RESERVATION_FAILED` (published inline, no transaction)
- `PaymentFacade.handleCallback()` → publishes `PaymentResultEvent` → `PaymentEventListener` (AFTER_COMMIT) → `RESERVATION_SUCCESS` or `RESERVATION_FAILED`
- `ReservationFacade.cancel()` → `RESERVATION_CANCELLED` (published after inner `@Transactional` completes)

## API Endpoints

**Auth (public)**
- `POST /api/v1/auth/signup` — body: `{ email, password }`; returns 201 on success, 409 if email taken
- `POST /api/v1/auth/login` — body: `{ email, password }`; returns `{ userId, accessToken }`, 401 on bad credentials

**Queue** (JWT required)
- `POST /api/v1/queue/enter?userId={userId}` — Enter waiting queue; returns `READY` if already active, else `WAITING` with rank
- `GET /api/v1/queue/status?userId={userId}` — Check queue status and active user count
- `DELETE /api/v1/queue?userId={userId}` — Leave queue and release active slot

**Seats** (JWT required)
- `GET /api/v1/seats` — All seats with real-time status (DB metadata + Redis cache)
- `GET /api/v1/seats/{seatId}` — Single seat
- `GET /api/v1/seats/available` — Only available seats

**Reservations** (JWT required)
- `POST /api/v1/reservations/reserve` — body: `{ seatId, userId }`; returns `reservationId`. 409 on conflict, 403 if not active queue user
- `POST /api/v1/reservations/cancel` — body: `{ seatId, userId }`

**Payments**
- `POST /api/v1/payments/request` *(JWT required)* — body: `{ reservationId, cardType, cardNo, amount }`; returns `{ transactionKey }`
- `POST /api/v1/payments/callback` *(public — PG server calls this)* — body: `{ transactionKey, status }`

**SSE** (public)
- `GET /api/v1/sse/seats` — Subscribe to real-time seat status stream (text/event-stream)

## Waiting Queue System

Redis-based virtual waiting room limiting concurrent reservation processing:

- **Data structures**: Sorted set (`ticket:waiting:queue`) stores waiting users by timestamp; individual TTL keys (`ticket:active:users:{userId}`) track active users
- **Capacity**: Max 200 concurrent active users
- **Scheduler**: `QueueScheduler` runs every 1s, promotes waiting → active up to capacity
- **Active user TTL**: 5 minutes; after TTL expires the slot is freed automatically
- **Reservation guard**: `ReservationFacade` checks `WaitingQueueService.isAllowed()` before proceeding — throws `IllegalStateException` (→ 403) if user is not active

## Important Notes

- **DataInitializer** creates 100 seats (`1번 좌석` → `100번 좌석`) on every application startup
- **PaymentService** simulates 80% success rate (used in reservation flow for mock payment)
- **SeatQueryService** composes DB metadata with Redis `state:seat:{seatId}` for real-time seat status
- Redis state has a 5-minute TTL to prevent stale locks; lock timeouts are 1s wait / 2s hold
- All infrastructure must be running via docker-compose before starting the application

### Kafka Considerations

- **Event publishing is fire-and-forget**: Main reservation flow doesn't wait for Kafka confirmation
- **Manual commit mode**: Consumer commits only after successful processing to prevent message loss
- **Partitioning**: Events for the same seat go to the same partition (preserves ordering)
- **Error handling**: Failed event processing doesn't commit offset → message will be reprocessed
- **Topic creation**: `reservation-events` topic must exist before first publish (auto-create enabled in Kafka config)
