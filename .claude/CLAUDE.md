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
- **Redis Preemptive State**: First-level filter to reject already-reserved seats without DB hit (`state:seat:{seatId}`)
- **TTL Strategy**: Redis state expires after 5 minutes to prevent stale locks

**4. Event-Driven Architecture (Kafka)**
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

### Critical Reservation Flow

1. **Lock Acquisition**: Try to acquire Redisson lock (1s wait, 2s hold)
2. **Redis Check**: Query `state:seat:{seatId}` for quick rejection if reserved
3. **DB Transaction**: Call `ReservationService.reserve()` to persist seat + reservation
4. **Update Cache**: Set Redis state to `RESERVED` with 5-minute TTL
5. **Publish Event**: Send `RESERVATION_SUCCESS` event to Kafka (async)
6. **Lock Release**: Always release lock in `finally` block

This approach decouples reservation processing from side effects (notifications, analytics) and provides fast response times.

### Domain Model Conventions

**Service Layer:**
- Default: `@Transactional(readOnly = true)` at class level
- CUD operations: Override with `@Transactional` on method

**Entity Layer:**
- Use domain methods for state transitions (not setters)
- Throw domain exceptions (e.g., `IllegalStateException`) for business rule violations
- Example: `seat.reserve()` instead of `seat.setReserved(true)`

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

Event publishing happens at three points in `ReservationFacade`:
1. Lock acquisition timeout → Publish `RESERVATION_FAILED`
2. Redis state already reserved → Publish `RESERVATION_FAILED`
3. Successful DB commit → Publish `RESERVATION_SUCCESS`
4. Exception during reservation → Publish `RESERVATION_FAILED`

## API Endpoints

**Queue (prerequisite before reserving)**
- `POST /api/v1/queue/enter?userId={userId}` — Enter waiting queue; returns `READY` if already active, else `WAITING` with rank
- `GET /api/v1/queue/status?userId={userId}` — Check queue status and active user count
- `DELETE /api/v1/queue?userId={userId}` — Leave queue and release active slot

**Seats (read-only)**
- `GET /api/v1/seats` — All seats with real-time status (DB metadata + Redis state)
- `GET /api/v1/seats/{seatId}` — Single seat
- `GET /api/v1/seats/available` — Only available seats

**Reservations**
- `POST /api/v1/reservations/reserve` — Reserve a seat; body: `{ seatId, userId }`. Returns 200 on success, 409 on conflict, 403 if not an active queue user
- `POST /api/v1/reservations/cancel` — Cancel reservation; body: `{ seatId, userId }`

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
