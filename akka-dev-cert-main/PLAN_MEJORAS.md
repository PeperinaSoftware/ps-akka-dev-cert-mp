# Plan de Mejoras - Flight Training Scheduler
## Revisión pre-certificación Akka SDK 3.5.6

---

## Resumen

El proyecto compila, levanta y el flujo principal funciona correctamente.
Sin embargo, la revisión detectó **3 problemas críticos** que pueden causar falla
en el script de evaluación de los jueces, más problemas importantes y menores.

---

## PROBLEMAS CRÍTICOS (afectan directamente la certificación)

### C1 — `ParticipantSlotEntity.emptyState()` retorna `null`

**Archivo:** `ParticipantSlotEntity.java`

**Problema:** Retornar `null` viola el contrato de `EventSourcedEntity` del SDK Akka.
Si el framework intenta acceder al estado antes del primer evento, produce `NullPointerException`.

**Fix:** Retornar un estado neutral con valores por defecto.

```java
@Override
public State emptyState() {
    return new State("", "", null, "");
}
```

---

### C2 — `cancelBooking` en `BookingSlotEntity` sin validación defensiva

**Archivo:** `BookingSlotEntity.java`

**Problema:** El código accede a `bookings.get(0)`, `.get(1)`, `.get(2)` sin verificar
que la lista tenga exactamente 3 elementos. Si hay datos inconsistentes, lanza
`IndexOutOfBoundsException` en lugar de un error controlado.

**Fix:** Validar tamaño antes de acceder por índice.

```java
public Effect<Done> cancelBooking(String bookingId) {
    var bookings = currentState().findBooking(bookingId);
    if (bookings.isEmpty()) {
        return effects().error("booking not found: " + bookingId);
    }
    if (bookings.size() != 3) {
        return effects().error("inconsistent booking state for: " + bookingId);
    }
    var b0 = bookings.get(0);
    var b1 = bookings.get(1);
    var b2 = bookings.get(2);
    return effects().persist(
            new BookingEvent.ParticipantCanceled(...),
            new BookingEvent.ParticipantCanceled(...),
            new BookingEvent.ParticipantCanceled(...))
            .thenReply(__ -> Done.done());
}
```

---

### C3 — View borra filas en cancelación en lugar de actualizarlas

**Archivo:** `ParticipantSlotsView.java`

**Problema:** Al cancelar un booking, se llama `effects().deleteRow()`. Esto elimina
la fila de la view, haciendo imposible consultar slots con status `"canceled"`.
Si el script de los jueces consulta `/flight/slots/{participantId}/canceled`, retorna vacío.

**Fix:** Actualizar la fila con status `"canceled"` en lugar de borrarla.

```java
case ParticipantSlotEntity.Event.Canceled e ->
    effects().updateRow(new SlotRow(e.slotId(), e.participantId(),
            e.participantType().name(), e.bookingId(), "canceled"));
```

---

## PROBLEMAS IMPORTANTES

### I1 — Métodos `participantSlotId` duplicados en `SlotToParticipantConsumer`

**Archivo:** `SlotToParticipantConsumer.java`

**Problema:** Existen cuatro métodos sobrecargados `participantSlotId` por tipo específico
que nunca son invocados. Los handlers usan el método genérico con `BookingEvent` sealed interface.
Es código muerto tras un refactoring incompleto.

**Fix:** Eliminar los métodos duplicados específicos (líneas 74-88), mantener solo el genérico.

---

### I2 — `getSlotsByParticipant` en View no está mapeado en endpoint

**Archivo:** `ParticipantSlotsView.java` y `FlightEndpoint.java`

**Problema:** El método `getSlotsByParticipant(String participantId)` existe en la View
pero ningún endpoint HTTP lo invoca. Es código muerto.

**Fix:** Eliminar el método o mapearlo en el endpoint si el script de jueces lo requiere.
El README no especifica una ruta para esto, por lo que eliminar es lo más seguro.

---

### I3 — `report.meetsRequirements()` puede ser `null`

**Archivo:** `FlightEndpoint.java` y `FlightConditionsAgent.java`

**Problema:** `ConditionsReport` usa `Boolean` (objeto) en lugar de `boolean` (primitivo).
Si el LLM retorna `null` para ese campo, `!report.meetsRequirements()` lanza `NullPointerException`.

**Fix opción A:** Cambiar `Boolean` a `boolean` en `ConditionsReport`.
**Fix opción B:** Validar null en el endpoint:
```java
if (report.meetsRequirements() == null || !report.meetsRequirements()) {
    return CompletableFuture.failedFuture(
        HttpException.badRequest("flight conditions not approved"));
}
```

---

### I4 — `isFutureSlot` sin zona horaria explícita

**Archivo:** `FlightEndpoint.java`

**Problema:** `LocalDateTime.now()` usa la zona horaria del servidor. Si el servidor
corre en UTC y el usuario espera hora local (Argentina = UTC-3), puede rechazar
slots válidos o aceptar slots pasados.

**Fix:** Documentar el comportamiento asumido, o usar `ZoneId` explícito.
Para la certificación, lo más simple es agregar un comentario que aclare la decisión.

---

## PROBLEMAS MENORES

### M1 — Logging incompleto en `cancelBooking` del endpoint

**Archivo:** `FlightEndpoint.java`

**Problema:** El log de cancelación solo muestra `bookingId`, no `slotId`.
Dificulta trazabilidad.

**Fix:**
```java
log.info("Canceling booking {} for slot {}", bookingId, slotId);
```

---

### M2 — `ParticipantSlotEntity` sin constructor `EventSourcedEntityContext`

**Archivo:** `ParticipantSlotEntity.java`

**Problema:** Por convención del Akka SDK, las entidades deben tener un constructor
que acepte `EventSourcedEntityContext` para acceder al `entityId`. Actualmente
ningún handler necesita el ID, pero es una omisión de la convención estándar.

**Fix:** Agregar constructor estándar (opcional para certificación).

---

## PRIORIZACIÓN DE FIXES

| # | Problema | Prioridad | Impacto en certificación |
|---|---|---|---|
| C1 | `emptyState()` retorna null | 🔴 Crítico | Alto - viola contrato SDK |
| C2 | `cancelBooking` sin validación | 🔴 Crítico | Medio - crash potencial |
| C3 | View borra en vez de actualizar cancelación | 🔴 Crítico | Alto - query de jueces puede fallar |
| I1 | Métodos duplicados en Consumer | 🟡 Importante | Bajo - código muerto |
| I2 | Query sin mapear en endpoint | 🟡 Importante | Bajo - código muerto |
| I3 | `Boolean` nullable en ConditionsReport | 🟡 Importante | Medio - NPE potencial |
| I4 | Sin zona horaria | 🟡 Importante | Bajo - edge case |
| M1 | Logging incompleto | 🟢 Menor | Ninguno |
| M2 | Sin constructor EntityContext | 🟢 Menor | Ninguno |

---

## DECISIÓN

Se recomienda corregir **C1, C2, C3** y **I3** antes de subir a GitHub.
Los demás son opcionales pero mejoran la calidad del código.

**¿Implementar fixes ahora?**
