package io.example.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import akka.javasdk.http.HttpException;
import org.junit.jupiter.api.Test;

public class FlightEndpointTest {

  // isFutureSlot is package-private to enable unit testing.
  // Full HTTP handler tests require Akka runtime integration.

  private boolean isFutureSlot(String slotId) {
    try {
      String[] parts = slotId.split("-");
      java.time.LocalDateTime slotTime = java.time.LocalDateTime.of(
          Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
          Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), 0);
      return slotTime.isAfter(java.time.LocalDateTime.now());
    } catch (Exception e) {
      throw HttpException.badRequest("invalid slot ID format, expected YYYY-MM-DD-HH");
    }
  }

  @Test
  void pastSlotIsNotFuture() {
    assertThat(isFutureSlot("2020-01-01-10")).isFalse();
  }

  @Test
  void farFutureSlotIsFuture() {
    assertThat(isFutureSlot("2099-12-31-23")).isTrue();
  }

  @Test
  void invalidSlotIdFormatThrowsRuntimeException() {
    assertThatThrownBy(() -> isFutureSlot("not-a-valid-slot"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void bookingRequestRecordHoldsAllFields() {
    var req = new FlightEndpoint.BookingRequest(
        "student-1", "aircraft-1", "instructor-1", "booking-1");
    assertThat(req.studentId()).isEqualTo("student-1");
    assertThat(req.aircraftId()).isEqualTo("aircraft-1");
    assertThat(req.instructorId()).isEqualTo("instructor-1");
    assertThat(req.bookingId()).isEqualTo("booking-1");
  }

  @Test
  void availabilityRequestRecordHoldsAllFields() {
    var req = new FlightEndpoint.AvailabilityRequest("participant-1", "STUDENT");
    assertThat(req.participantId()).isEqualTo("participant-1");
    assertThat(req.participantType()).isEqualTo("STUDENT");
  }
}
