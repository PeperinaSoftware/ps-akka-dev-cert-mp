# Architecture & Design Decisions

## Overview

This implementation of the Flight Training Scheduler is built on **Akka SDK 3.5.6**, leveraging its event-sourced entity model, reactive views, event-driven consumers, and AI agent capabilities. The architecture follows a CQRS pattern where write operations flow through event-sourced entities and read operations are served by eventually-consistent views, with an asynchronous consumer pipeline bridging the two.

---

## Component Design Decisions

### BookingSlotEntity — Event-Sourced Entity

**Decision:** Model the timeslot as an `EventSourcedEntity<Timeslot, BookingEvent>` rather than a simple key-value entity.

**Rationale:** A timeslot is inherently a transactional aggregate that must enforce business invariants: no double booking, all three participant types required, and bookings only for future slots. Event sourcing provides an immutable audit trail of every state transition, which is critical in a scheduling domain where disputes about booking history can arise. The `Timeslot` domain object acts as the aggregate root, encapsulating all invariant enforcement through `isBookable()` and `findBooking()`.

**Key decisions:**
- `bookSlot` emits **three** `ParticipantBooked` events atomically — one per participant type — rather than a single composite event. This enables downstream consumers to process each participant independently and keeps events fine-grained.
- `cancelBooking` accepts a `Command.CancelBooking` record (not a raw `String`) for consistency with the sealed `Command` interface pattern and to maintain a uniform command model.
- `cancelBooking` validates `bookings.size() == 3` before indexing, defending against inconsistent state that could arise from partial event replay or infrastructure failures.
- `emptyState()` returns a `Timeslot` with pre-allocated `HashSet` instances, avoiding null checks and NPEs throughout command handlers.
- `getSlot()` returns `ReadOnlyEffect<Timeslot>` — correctly using the read-only variant since it does not produce events, signaling intent to the framework and preventing accidental state mutation.

---

### ParticipantSlotEntity — Derived Event-Sourced Entity

**Decision:** Introduce a secondary entity keyed by `{slotId}-{participantId}` to track per-participant slot status.

**Rationale:** The `BookingSlotEntity` is keyed by `slotId`, making it efficient for slot-level operations but unsuitable for participant-centric queries (e.g., "all slots booked by Alice"). Rather than overloading the booking entity with cross-cutting query concerns, a dedicated derived entity maintains a simple, single-purpose state per participant per slot. This separation of concerns aligns with the Single Responsibility Principle and avoids bloating the primary entity.

**Key decisions:**
- `emptyState()` returns `new State("", "", null, "")` — a neutral non-null state that satisfies the SDK contract and prevents NPEs before the first event is applied.
- Status transitions (`available` → `booked` → `canceled`, `available` → `removed`) are modeled as explicit string values rather than an enum to keep the view query layer simple and to allow external systems to reason about status values without enum mapping.
- The constructor accepts `EventSourcedEntityContext` per SDK convention, making the `entityId` available for future use without requiring a refactor.

---

### SlotToParticipantConsumer — Event Bridge Consumer

**Decision:** Implement a `Consumer` that subscribes to `BookingSlotEntity` events and dispatches commands to `ParticipantSlotEntity`, rather than having the booking entity directly call the participant entity.

**Rationale:** Direct entity-to-entity calls from within a command handler would introduce synchronous coupling and violate the reactive model. By using an asynchronous consumer, the booking entity remains decoupled from participant tracking concerns. The consumer guarantees at-least-once delivery, ensuring no events are lost even under partial failures.

**Key decisions:**
- **Four individual `onEvent` methods** (one per concrete event type) rather than a single method accepting the sealed `BookingEvent` interface. The Akka SDK dispatches consumers by method signature — a single method accepting the sealed interface would not be invoked for individual subtypes.
- The `participantSlotId` derivation logic (`{slotId}-{participantId}`) is intentionally package-private to enable unit testing without requiring a full runtime.
- `effects().asyncDone()` is used throughout, correctly handling the asynchronous nature of entity command invocations via `ComponentClient`.

---

### ParticipantSlotsView — Queryable Read Model

**Decision:** Implement a `View` backed by a `TableUpdater<SlotRow>` subscribed to `ParticipantSlotEntity` events, with a single query filtered by both `participantId` and `status`.

**Rationale:** Views in Akka SDK provide eventually-consistent, SQL-queryable projections of entity state. By subscribing to the derived `ParticipantSlotEntity` rather than the `BookingSlotEntity`, the view is decoupled from the booking aggregate and can evolve independently. The `participant_slots` table is shaped for the exact query pattern required by the endpoint.

**Key decisions:**
- **Canceled rows are updated, not deleted.** `effects().updateRow(...)` with `status = "canceled"` preserves historical data and allows the query endpoint to serve `GET /flight/slots/{participantId}/canceled`. Deleting rows on cancellation would make this query impossible.
- `UnmarkedAvailable` events trigger `effects().deleteRow()` — this is intentional. An unmarked availability has no meaningful status to preserve; the participant simply withdrew before any booking was made.
- `bookingId` is stored as an empty string `""` for `available` rows (not `null`) to satisfy the non-null constraint of the `SlotRow` record and avoid serialization issues.
- The `getSlotsByParticipant(String participantId)` query method was removed — it was unreferenced dead code not mapped to any endpoint, reducing surface area and potential confusion.

---

### FlightConditionsAgent — AI Agent

**Decision:** Implement the flight conditions evaluator as an `Agent` using Akka SDK's built-in LLM integration with `@FunctionTool` for weather forecast retrieval.

**Rationale:** The agent pattern cleanly separates the AI reasoning loop from the HTTP request-response cycle. By using `responseAs(ConditionsReport.class)`, the framework handles JSON deserialization from the LLM response, ensuring type safety. The `@FunctionTool` mechanism allows the LLM to autonomously decide when to call the weather function based on the system prompt instructions.

**Key decisions:**
- `ConditionsReport` uses `boolean` (primitive) rather than `Boolean` (object). A nullable `Boolean` would cause `NullPointerException` in the endpoint when unboxing `!report.meetsRequirements()` if the LLM fails to include the field in its JSON response.
- The system prompt enforces **JSON-only output** explicitly, preventing the LLM from returning natural language responses that would fail deserialization.
- Agent sessions are scoped to `{slotId}-{bookingId}`, ensuring each booking attempt gets a fresh, isolated conversation context without cross-contaminating state between requests.
- Weather simulation uses **daytime/nighttime hour logic** for deterministic, date-independent results — slots with hour 06–18 return good VFR conditions (approve), hours outside that window return poor conditions (reject). This avoids coupling test scenarios to specific calendar dates.
- `getWeatherForecast` is package-private to enable unit testing of the weather logic without requiring LLM invocation.

---

### FlightEndpoint — HTTP API

**Decision:** All handler methods return `CompletionStage<T>` rather than blocking return types.

**Rationale:** Every handler ultimately delegates to a `ComponentClient` call, which is inherently asynchronous. Blocking would tie up Akka's dispatcher threads, degrading throughput under load and potentially causing deadlocks. `CompletionStage` chains (`thenCompose`, `thenApply`) allow the framework to schedule work non-blockingly.

**Key decisions:**
- `createBooking` chains **two async calls** sequentially: first the LLM agent, then the entity command. `thenCompose` is used (not `thenApply`) because the second operation is itself asynchronous, avoiding nested futures.
- `isFutureSlot` validation is performed **synchronously before** the async chain begins. Failing fast on an invalid slot ID avoids unnecessary LLM invocations, which carry latency and cost.
- Slot IDs and server time are both treated as UTC. Clients must submit `slotId` values in UTC format (`YYYY-MM-DD-HH`).
- `isFutureSlot` is package-private to enable isolated unit testing of the temporal validation logic.
- `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))` explicitly opens the endpoint to public access, as required by the certification specification.

---

## Testing Strategy

Unit tests cover all source files using the Akka SDK `EventSourcedTestKit` for entities and plain JUnit 5 for domain and utility logic. The test suite runs **52 tests** across 9 test classes, one per production file:

| Layer | Test Class | Approach |
|---|---|---|
| Domain | `TimeslotTest`, `BookingEventTest`, `ParticipantTest` | Pure unit tests, no framework |
| Entity | `BookingSlotEntityTest`, `ParticipantSlotEntityTest` | `EventSourcedTestKit` |
| Agent | `FlightConditionsAgentTest` | Direct instantiation, package-private access |
| View | `ParticipantSlotsViewTest` | Record/data contract tests |
| Consumer | `SlotToParticipantConsumerTest` | Package-private key derivation logic |
| Endpoint | `FlightEndpointTest` | Package-private temporal logic + record contracts |

Full integration testing of the consumer dispatch and view propagation requires the Akka runtime and is validated manually via the curl-based testing guide.

---

## Development Notes

This implementation was built using **AI-assisted Software-Driven Development (SDD)** with **Claude Sonnet 4.6** as the primary development assistant. All architectural decisions, component design, SDK usage patterns, and test coverage described in this document were defined, reviewed, and validated through an iterative SDD workflow.

The `FlightConditionsAgent` uses **Claude Haiku 4.5** (`claude-haiku-4-5-20251001`) as its LLM backend for VFR conditions evaluation. Haiku was selected for its low latency and cost efficiency in a high-frequency, structured JSON response use case where reasoning depth is not a primary requirement.
