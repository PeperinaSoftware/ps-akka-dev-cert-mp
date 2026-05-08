# End-to-End Testing Guide — Flight Training Scheduler
## Pre-certification verification checklist

---

## 1. Prerequisites

- Java 21 available via sdkman
- Active Anthropic API key with available credits
- Maven 3.9+
- Service **stopped** before compiling

---

## 2. Configure Java 21 (once per terminal session)

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.1-zulu"
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify it is set correctly:
```bash
java -version
```
✅ Expected: `openjdk version "21..."`

---

## 3. Compile

```bash
mvn compile
```

✅ Expected: `BUILD SUCCESS`

---

## 4. Run unit tests

```bash
mvn test
```

✅ Expected: `Tests run: 52, Failures: 0, Errors: 0` + `BUILD SUCCESS`

---

## 5. Start the service

In a separate terminal, configure Java 21 and the API key:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.1-zulu"
export PATH="$JAVA_HOME/bin:$PATH"
export ANTHROPIC_API_KEY="your-api-key-here"

mvn compile && mvn exec:java
```

✅ Expected in the logs:
```
Akka Runtime started at 127.0.0.1:9000
```

Leave this terminal open and open a new one for the curl commands.

---

## 6. Environment variables for curl commands

In the curl terminal, define these values for reuse:

```bash
# Daytime slot (hour 10) → good VFR conditions → booking should APPROVE
SLOT_OK="2026-08-10-10"

# Nighttime slot (hour 23) → poor conditions → booking should REJECT
SLOT_BAD="2026-08-11-23"

# Past slot → must be rejected before calling the agent
SLOT_PAST="2020-01-01-10"

STUDENT="alice"
AIRCRAFT="superplane"
INSTRUCTOR="superteacher"
BOOKING_ID="booking-cert-01"
BASE="http://localhost:9000"
```

---

## 7. Full test flow

### STEP 1 — Mark availability

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$STUDENT\", \"participantType\": \"student\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Expected: `200`

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$AIRCRAFT\", \"participantType\": \"aircraft\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Expected: `200`

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$INSTRUCTOR\", \"participantType\": \"instructor\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Expected: `200`

---

### STEP 2 — Verify slot internal state

```bash
curl -s $BASE/flight/availability/$SLOT_OK
```

✅ Expected:
```json
{
  "bookings": [],
  "available": [
    { "id": "alice", "participantType": "STUDENT" },
    { "id": "superplane", "participantType": "AIRCRAFT" },
    { "id": "superteacher", "participantType": "INSTRUCTOR" }
  ]
}
```
> Participant order may vary (backed by a Set).

---

### STEP 3 — Query the View (slots by participant)

Wait 2–3 seconds for the consumer to propagate events to the view.

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/available
```

✅ Expected:
```json
{
  "slots": [
    {
      "slotId": "2026-08-10-10",
      "participantId": "alice",
      "participantType": "STUDENT",
      "bookingId": "",
      "status": "available"
    }
  ]
}
```

```bash
curl -s $BASE/flight/slots/$AIRCRAFT/available
curl -s $BASE/flight/slots/$INSTRUCTOR/available
```

✅ Expected: each shows its own row with `status: "available"`.

---

### STEP 4 — Attempt booking with a PAST slot (must fail)

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"booking-past\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_PAST
```

✅ Expected: `400`

---

### STEP 5 — Attempt booking with BAD conditions (nighttime slot)

First, mark availability for the nighttime slot:

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$STUDENT\", \"participantType\": \"student\"}" \
  $BASE/flight/availability/$SLOT_BAD

curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$AIRCRAFT\", \"participantType\": \"aircraft\"}" \
  $BASE/flight/availability/$SLOT_BAD

curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$INSTRUCTOR\", \"participantType\": \"instructor\"}" \
  $BASE/flight/availability/$SLOT_BAD
```

Now attempt the booking:

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"booking-bad\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_BAD
```

✅ Expected: `400` with a message containing `flight conditions not approved`

---

### STEP 6 — Successful booking (daytime slot, good conditions)

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"$BOOKING_ID\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_OK
```

✅ Expected: `201`

> In the service logs you should see:
> ```
> Creating booking for slot 2026-08-10-10: ...
> Flight conditions report for slot 2026-08-10-10: meetsRequirements=true
> ```

---

### STEP 7 — Verify booked status in the View

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/booked
```

✅ Expected:
```json
{
  "slots": [
    {
      "slotId": "2026-08-10-10",
      "participantId": "alice",
      "participantType": "STUDENT",
      "bookingId": "booking-cert-01",
      "status": "booked"
    }
  ]
}
```

```bash
curl -s $BASE/flight/slots/$AIRCRAFT/booked
curl -s $BASE/flight/slots/$INSTRUCTOR/booked
```

✅ Expected: all 3 participants show `status: "booked"` with the same `bookingId`.

---

### STEP 8 — Cancel the booking

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -X DELETE \
  $BASE/flight/bookings/$SLOT_OK/$BOOKING_ID
```

✅ Expected: `200`

In the service logs you should see 3 lines:
```
Canceling booking booking-cert-01 from slot 2026-08-10-10
Canceling booking booking-cert-01 for participant alice
Canceling booking booking-cert-01 for participant superplane
Canceling booking booking-cert-01 for participant superteacher
```

---

### STEP 9 — Verify CANCELED status in the View

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/canceled
```

✅ Expected:
```json
{
  "slots": [
    {
      "slotId": "2026-08-10-10",
      "participantId": "alice",
      "participantType": "STUDENT",
      "bookingId": "booking-cert-01",
      "status": "canceled"
    }
  ]
}
```

```bash
curl -s $BASE/flight/slots/$AIRCRAFT/canceled
curl -s $BASE/flight/slots/$INSTRUCTOR/canceled
```

✅ Expected: all 3 participants show `status: "canceled"`.

---

### STEP 10 — Verify the slot is empty

```bash
curl -s $BASE/flight/availability/$SLOT_OK
```

✅ Expected:
```json
{
  "bookings": [],
  "available": []
}
```

---

## 8. Final checklist

| # | Verification | Result |
|---|---|---|
| 1 | `mvn compile` → BUILD SUCCESS | ⬜ |
| 2 | `mvn test` → 52 tests, 0 failures | ⬜ |
| 3 | Service starts on port 9000 | ⬜ |
| 4 | Mark availability returns 200 | ⬜ |
| 5 | View shows `available` slots for all 3 participants | ⬜ |
| 6 | Past slot returns 400 | ⬜ |
| 7 | Nighttime slot booking returns 400 (agent rejects) | ⬜ |
| 8 | Daytime slot booking returns 201 | ⬜ |
| 9 | View shows `booked` slots for all 3 participants | ⬜ |
| 10 | Cancel returns 200 | ⬜ |
| 11 | View shows `canceled` slots for all 3 participants | ⬜ |
| 12 | Slot internal state is empty after cancellation | ⬜ |

All 12 items ✅ → project is ready for certification.
