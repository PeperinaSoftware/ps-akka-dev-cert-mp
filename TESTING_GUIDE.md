# Testing Guide — Flight Training Scheduler
## Chequeo final pre-certificación

---

## 1. Requisitos previos

- Java 21 disponible vía sdkman
- API Key de Anthropic activa y con crédito
- Maven 3.9+
- Servicio **detenido** antes de compilar

---

## 2. Configurar Java 21 (hacer una sola vez por terminal)

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.1-zulu"
export PATH="$JAVA_HOME/bin:$PATH"
```

Verificá que quedó bien:
```bash
java -version
```
✅ Esperado: `openjdk version "21..."`

---

## 3. Compilar

```bash
mvn compile
```

✅ Esperado: `BUILD SUCCESS`

---

## 4. Correr los tests unitarios

```bash
mvn test
```

✅ Esperado: `Tests run: 51, Failures: 0, Errors: 0` + `BUILD SUCCESS`

---

## 5. Levantar la aplicación

En una terminal separada, configurá Java 21 y la API key:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.1-zulu"
export PATH="$JAVA_HOME/bin:$PATH"
export ANTHROPIC_API_KEY="tu-api-key-aqui"

mvn compile && mvn exec:java
```

✅ Esperado en los logs:
```
Akka Runtime started at 127.0.0.1:9000
```

Dejá esta terminal abierta y abrí otra para los curls.

---

## 6. Variables de entorno para los curls

En la terminal de curls, definí estos valores para reutilizarlos:

```bash
# Slot en día par → condiciones buenas → booking debe APROBAR
SLOT_OK="2026-08-10-10"

# Slot en hora nocturna → condiciones malas → booking debe RECHAZAR
SLOT_BAD="2026-08-11-23"

# Slot en el pasado → debe rechazarse antes de llamar al agente
SLOT_PAST="2020-01-01-10"

STUDENT="alice"
AIRCRAFT="superplane"
INSTRUCTOR="superteacher"
BOOKING_ID="booking-cert-01"
BASE="http://localhost:9000"
```

---

## 7. Flujo completo de prueba

### PASO 1 — Marcar disponibilidad

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$STUDENT\", \"participantType\": \"student\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Esperado: `200`

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$AIRCRAFT\", \"participantType\": \"aircraft\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Esperado: `200`

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"participantId\": \"$INSTRUCTOR\", \"participantType\": \"instructor\"}" \
  $BASE/flight/availability/$SLOT_OK
```
✅ Esperado: `200`

---

### PASO 2 — Verificar estado interno del slot

```bash
curl -s $BASE/flight/availability/$SLOT_OK ```

✅ Esperado:
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
> El orden de los participantes puede variar (es un Set).

---

### PASO 3 — Consultar la View (slots por participante)

Esperá 2-3 segundos para que el consumer propague los eventos a la view.

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/available ```

✅ Esperado:
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
curl -s $BASE/flight/slots/$AIRCRAFT/available curl -s $BASE/flight/slots/$INSTRUCTOR/available ```

✅ Esperado: cada uno muestra su fila con `status: "available"`.

---

### PASO 4 — Intentar booking con slot PASADO (debe fallar)

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"booking-past\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_PAST
```

✅ Esperado: `400`

---

### PASO 5 — Intentar booking con condiciones MALAS (hora nocturna)

Primero marcá disponibilidad en el slot impar:

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

Ahora intentá el booking:

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"booking-bad\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_BAD
```

✅ Esperado: `400` con mensaje que contiene `flight conditions not approved`

---

### PASO 6 — Booking EXITOSO (día par, condiciones buenas)

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -X POST -d "{\"bookingId\": \"$BOOKING_ID\", \"studentId\": \"$STUDENT\", \"aircraftId\": \"$AIRCRAFT\", \"instructorId\": \"$INSTRUCTOR\"}" \
  $BASE/flight/bookings/$SLOT_OK
```

✅ Esperado: `201`

> En los logs del servicio deberías ver la llamada al agente LLM y la respuesta JSON con `meetsRequirements: true`.

---

### PASO 7 — Verificar estado booked en la View

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/booked ```

✅ Esperado:
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
curl -s $BASE/flight/slots/$AIRCRAFT/booked curl -s $BASE/flight/slots/$INSTRUCTOR/booked ```

✅ Esperado: los 3 participantes aparecen con `status: "booked"` y el mismo `bookingId`.

---

### PASO 8 — Cancelar el booking

```bash
curl -s -w "\nHTTP: %{http_code}\n" \
  -X DELETE \
  $BASE/flight/bookings/$SLOT_OK/$BOOKING_ID
```

✅ Esperado: `200`

En los logs del servicio deberías ver 3 líneas:
```
Canceling booking booking-cert-01 for slot ...  (alice)
Canceling booking booking-cert-01 for slot ...  (superplane)
Canceling booking booking-cert-01 for slot ...  (superteacher)
```

---

### PASO 9 — Verificar status CANCELED en la View

```bash
sleep 3
curl -s $BASE/flight/slots/$STUDENT/canceled ```

✅ Esperado:
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
curl -s $BASE/flight/slots/$AIRCRAFT/canceled curl -s $BASE/flight/slots/$INSTRUCTOR/canceled ```

✅ Esperado: los 3 participantes aparecen con `status: "canceled"`.

---

### PASO 10 — Verificar que el slot quedó vacío

```bash
curl -s $BASE/flight/availability/$SLOT_OK ```

✅ Esperado:
```json
{
  "bookings": [],
  "available": []
}
```

---

## 8. Checklist final

| # | Verificación | Resultado |
|---|---|---|
| 1 | `mvn compile` BUILD SUCCESS | ⬜ |
| 2 | `mvn test` 51 tests, 0 failures | ⬜ |
| 3 | Servicio levanta en puerto 9000 | ⬜ |
| 4 | Marcar disponibilidad retorna 200 | ⬜ |
| 5 | View muestra slots `available` | ⬜ |
| 6 | Slot pasado retorna 400 | ⬜ |
| 7 | Booking hora nocturna retorna 400 (agente rechaza) | ⬜ |
| 8 | Booking día par retorna 201 | ⬜ |
| 9 | View muestra slots `booked` para los 3 participantes | ⬜ |
| 10 | Cancelar retorna 200 | ⬜ |
| 11 | View muestra slots `canceled` para los 3 participantes | ⬜ |
| 12 | Slot interno queda vacío tras cancelación | ⬜ |

Si los 12 ítems están ✅, el proyecto está listo para certificación.

---

## 9. Datos para el mail de certificación

```
Para: certification@akka.io
Asunto: Akka Developer Certification Submission

Nombre: [Tu nombre]
Repositorio: https://github.com/PeperinaSoftware/ps-akka-dev-cert-mp
```
