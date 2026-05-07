# Implementation: Architecture & Design Decisions

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
- Status transitions (`available` → `booked` → `canceled`, `available` → `removed`) are modeled as explicit string values rather than an enum to keep the view query layer simple and to allow the LLM or external systems to reason about status values without enum mapping.
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
- Weather simulation uses even/odd day logic to produce deterministic, predictable results for testing — slots on even days approve, odd days reject.
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

Unit tests cover all source files using the Akka SDK `EventSourcedTestKit` for entities and plain JUnit 5 for domain and utility logic. The test suite runs **51 tests** across 9 test classes, one per production file:

| Layer | Test Class | Approach |
|---|---|---|
| Domain | `TimeslotTest`, `BookingEventTest`, `ParticipantTest` | Pure unit tests, no framework |
| Entity | `BookingSlotEntityTest`, `ParticipantSlotEntityTest` | `EventSourcedTestKit` |
| Agent | `FlightConditionsAgentTest` | Direct instantiation, package-private access |
| View | `ParticipantSlotsViewTest` | Record/data contract tests |
| Consumer | `SlotToParticipantConsumerTest` | Package-private key derivation logic |
| Endpoint | `FlightEndpointTest` | Package-private temporal logic + record contracts |

Full integration testing of the consumer dispatch and view propagation requires the Akka runtime and is validated manually via the curl script provided in this document.

---

## Development Notes

This implementation was built using **AI-assisted Software-Driven Development (SDD)** with **Claude Sonnet 4.6** as the primary development assistant. All architectural decisions, component design, SDK usage patterns, and test coverage described in this document were defined, reviewed, and validated through an iterative SDD workflow.

The `FlightConditionsAgent` uses **Claude Haiku 4.5** (`claude-haiku-4-5-20251001`) as its LLM backend for VFR conditions evaluation. Haiku was selected for its low latency and cost efficiency in a high-frequency, structured JSON response use case where reasoning depth is not a primary requirement.

---

# How to Get Certified

The Flight Training Scheduler project serves as the certification test for Akka developers. This certification process evaluates your ability to implement a real-world application using Akka SDK components given a set of requirements, scaffolding, and some starter classes.

## Getting Started

### Prerequisites

* Java 21, Eclipse Adoptium recommend
* Apache Maven version 3.9 or later
* `curl` command-line tool
* An API key and whatever configuration is necessary to communicate with an LLM model provider (e.g. OpenAI). Use one of our [supported models](https://doc.akka.io/java/agents.html#model). Nothing in this application should exceed free/trial limits.

Download the Akka CLI following the instructions [here](https://doc.akka.io/operations/cli/installation.html), and create any example project by running

```shell
akka code init
```

This will add your `repo.akka.io` token to your local user account's [Maven `settings.xml` file](https://maven.apache.org/settings.html).

Then, clone this template repository, which contains:

* Project structure and configuration
* Supporting scaffolds
* Documentation and requirements
* All non-Akka components

### Certification Requirements

Your task is to implement the following Akka SDK components:

* **Flights** [endpoint](https://doc.akka.io/java/http-endpoints.html)
* **BookingSlot** [entity](https://doc.akka.io/java/event-sourced-entities.html)
* **ParticipantSlots** [view](https://doc.akka.io/java/views.html)
* **FlightConditions** [agent](https://doc.akka.io/java/agents.html)

### Implementation Guidelines

* Adhere to the design specifications for each component
* Ensure all components work together as described
* Code must pass all tests if you provide them
* Maintain proper event flow and state management
* Handle all required operations correctly

### Submission Process

1. Complete your implementation
2. Test thoroughly as judges will be using a script to test edge cases
3. Upload your completed project to a public repository (e.g., GitHub)
4. Email [certification@akka.io](mailto:certification@akka.io) with:
   * Your contact information
   * Link to your public repository

### Evaluation

The certification team will review your implementation for:

* Correct functionality
* Proper use of Akka SDK components
* Code quality and organization
* Adherence to specified requirements
* Successful completion of the judge's test script

## Flight Training Scheduler App Design

Flight schools provide training to students looking to become pilots. While some of that training is in a classroom, most training takes place in a real plane. Scheduling this training can be a complex process and so your assignment is to create the backend for a flight training scheduler.

The core concept in this flight scheduler is that of a `Timeslot`. A timeslot is identified by a unique identifier, but the backend makes no actual calendar demands of a timeslot. This lets the application UI decide how it will deal with timeslots such as their start and end times. 

Participants will indicate their availability for a given timeslot. Once enough participants are available for a given slot, the student can then book that slot, confirming it. The following are the three types of participants that can mark availability and confirm timeslots:

* Students - One of two types of end users of the application
* Instructor
* Aircraft

A booking requires the availability of all three participant types. An important design decision to remember is that for a given timeslot, multiple aircraft, instructors, and students can all be available. The student then must indicate which aircraft and instructor they're reserving when they make a booking.

The ID of a timeslot **must** be a string representing a simplified date and time that takes the format `YYYY-MM-DD-HH`, where `HH` is the hour of day in 24-hour time. For example, the time slot ID of `2025-08-08-09`, represents a slot for August 8th, 2025 at 9am local time.

All interactions with the training flight booking system are done through an HTTP endpoint with the following API:

| Method | URL | Description |
|:-:|---|---|
| `POST` | `/flight/availability/{slotId}` | Adds an availability indication for a participant in a given slot | 
| `DELETE` | `/flight/availability/{slotId}` | Removes an availability indication for a participant in a given slot |
| `GET` | `/flight/availability/{slotId}` | Retrieves the availability status of a given slot |
| `POST` | `/flight/bookings/{slotId}` | Book a slot. Requires availability of the three indicated participants | 
| `DELETE` | `/flight/bookings/{slotId}/{bookingId}` | Cancels a booking for a given slot |
| `GET` | `/flight/slots/{participantId}/{status}` | Retrieves timeslot status for the given `participantId` with a status of `status` |


## Flight Training Scheduler Core Functions

The provided template repository contains all the business logic defined in domain objects. Do not modify the provided domain objects, your objective is to implement the necessary Akka SDK components that interact with the domain objects, processing requests, commands, and events.

### Availability Management

The application allows all participants to indicate their available time slots in a calendar system. Each participant can mark when they are free for training sessions, creating a pool of available time slots for each participant type.

### Booking System

Students can browse available time slots and create bookings. The system ensures that a valid reservation can only be created when all three required participants (student, instructor, and aircraft) have marked availability for the same time slot. Bookings are always for future time slots. The system also uses an agent to verify that flight conditions for the slot meet minimum requirements.

## Flight Training Scheduler Business Rules

### Scheduling Logic

* All reservations require exactly three participants: one each of student, instructor, and aircraft
* Participants can have multiple reservations
* Consecutive time slots are allowed
* No approval workflow is required
* No qualification matching is needed between participants

### Booking Management

* Bookings can only be created for future time slots
* Existing bookings can be canceled but not modified
* Cancellations can occur for any reason
* There are no restrictions on how far in advance slots can be booked
* The flight conditions agent must verify that conditions at the time of booking will meet requirements.

The system maintains consistency through Akka's concurrency management, ensuring double bookings cannot occur, and all participants remain correctly scheduled.

## Flight Training Scheduler Components to Implement
The following is a list of the components that need to be implemented in order for this solution to be considered complete. Scaffolding and appropriate placeholders will be there so you can supply the implementation without worrying about ceremony.

### Booking Slot Entity
The `BookingSlotEntity` component serves as the authority for a single instance of a time slot. A timeslot manages the list of participants that have been marked as `available` (ready to book) as well as those that have been converted to `booked` via the HTTP endpoint.

This entity maintains these two internal lists so that it can reject bad commands as well as commands that might violate system integrity or business rules.

### Flight Conditions Agent
Before a booking can be confirmed, the flight conditions must be checked. You will create a generative AI agent that communicates with an LLM to verify that flight conditions meet criteria. If the booking slot is too far in the future to predict conditions, then the agent will conditionally approve. For more details, see the comments in the agent placeholder.

### Participant Slot Entity
For view purposes we want to be able to query the list of timeslots for a given participant. For example, as a student I want to see the slots that I've marked as `available` as well as those that are actively booked.

Since the `BookingSlotEntity` is keyed to a single slot, we have the `ParticipantSlotEntity` which is keyed to a specific _slot-participant_ and it maintains an attribute of `status`. This entity is automatically maintained and doesn't have any endpoint interaction.

### Participant Slots View
The `ParticipantSlotsView` is a view that allows the endpoint to query data managed by events specific to the `ParticipantSlotEntity`. Each row in this view is keyed by `slotId-participantId` and has fields for the participant type and the slot status (`booked`, `available`).

### Slot-to-Participant Consumer
This consumer is responsible for taking events emitted by the `BookingSlotEntity` and invoking corresponding commands on the `ParticipantSlotEntity`, effectively normalizing the data so it can be queried and filtered by attributes smaller than the timeslot ID.

### Flight HTTP Endpoint
The public, RESTful API that provides consumers with access to the flight service.

## Booking Flight Training Reservations

To book a training flight:

* The `student` participant must be marked `available` for a given slot
* The `aircraft` participant must be marked `available` for the same slot
* The `instructor` participant must be marked `available` for that same slot
* A booking request is then made of the timeslot, containing the student, aircraft, and instructor IDs.
* For the proposed time slot, the flight conditions agent _must_ approve predicted conditions.

### Cancel a Booking
If a timeslot has a given booking then that booking can be canceled. The call to the HTTP endpoint's "create boooking" route requires the client to pass the booking ID so it will be able to use it for future calls such as `cancel`.

## Testing with Curl
The easiest way to make sure your flight service is performing as designed is to use some canned `curl` statements that we know produce predictable results.

Pick an appropriate timeslot to use. For the examples below, `2025-12-10-10` is used. If you are using these commands after that date, pick a new one in the future. 

Start by marking availability in the slot `bestslot` for 3 participants: `alice`, `superplane`, and `superteacher` for the `student`, `aircraft`, and `instructor` respectively.

```
curl -v -H "Content-Type: application/json" -X POST -d '{"participantId": "alice", "participantType": "student"}' localhost:9000/flight/availability/2025-12-10-10

curl -v -H "Content-Type: application/json" -X POST -d '{"participantId": "superplane", "participantType": "aircraft"}' localhost:9000/flight/availability/2025-12-10-10

curl -v -H "Content-Type: application/json" -X POST -d '{"participantId": "superteacher", "participantType": "instructor"}' localhost:9000/flight/availability/2025-12-10-10
```

Query the slot's internal state:
```
curl -H "Content-Type: application/json" localhost:9000/flight/availability/bestslot
```

```json
{
  "bookings": [],
  "available": [
    {
      "id": "alice",
      "participantType": "STUDENT"
    },
    {
      "id": "superteacher",
      "participantType": "INSTRUCTOR"
    },
    {
      "id": "superplane",
      "participantType": "AIRCRAFT"
    }
  ]
}
```

Now you can query for all of Alice's availability slots:
```
curl -v localhost:9000/flight/slots/alice/available
```

And the `superplane`:
```
curl -v localhost:9000/flight/slots/superplane/available
```

Now book the slot:
```
curl -v -H "Content-Type: application/json" localhost:9000/flight/bookings/2025-12-10-10 -d '{"bookingId": "booking4", "aircraftId": "superplane", "instructorId": "superteacher", "studentId": "alice"}'
```

Check alice's booked timeslots:
```
curl -v localhost:9000/flight/slots/alice/booked
```

The JSON output:
```json
{
  "slots": [
    {
      "slotId": "2025-12-10-10",
      "participantId": "alice",
      "participantType": "STUDENT",
      "bookingId": "booking4",
      "status": "booked"
    }
  ]
}
```
Note that there's enough information in the output of this timeslot query to cancel a booking. We got both the `slotId` and the `bookingId`.

Cancel the booking, which should result in all 3 participants having a canceled event:

```
curl -v -X DELETE -H "Content-Type: application/json" localhost:9000/flight/bookings/2025-12-10-10/booking4 
```

You'll see something like this in the service's log:

```

12:49:38.595 INFO  i.e.a.SlotToParticipantConsumer - Canceling booking booking4 for participant superteacher
12:49:38.609 INFO  i.e.a.SlotToParticipantConsumer - Canceling booking booking4 for participant superplane
12:49:38.614 INFO  i.e.a.SlotToParticipantConsumer - Canceling booking booking4 for participant alice
```

The `Timeslot`, which is the internal state of the Booking Slot Entity, should now be empty (no availability, no bookings):
```
curl -H "Content-Type: application/json" localhost:9000/flight/availability/2025-12-10-10
```

```json

{
  "bookings": [],
  "available": []
}
```
