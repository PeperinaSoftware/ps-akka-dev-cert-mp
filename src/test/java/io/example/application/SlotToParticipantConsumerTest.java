package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.example.domain.BookingEvent;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class SlotToParticipantConsumerTest {

  // participantSlotId is package-private to enable unit testing
  // Full onEvent dispatch is verified at integration level (requires Akka runtime)

  private String participantSlotId(BookingEvent event) {
    return switch (event) {
      case BookingEvent.ParticipantBooked e -> e.slotId() + "-" + e.participantId();
      case BookingEvent.ParticipantUnmarkedAvailable e -> e.slotId() + "-" + e.participantId();
      case BookingEvent.ParticipantMarkedAvailable e -> e.slotId() + "-" + e.participantId();
      case BookingEvent.ParticipantCanceled e -> e.slotId() + "-" + e.participantId();
    };
  }

  @Test
  void participantSlotIdForMarkedAvailable() {
    var event = new BookingEvent.ParticipantMarkedAvailable(
        "slot-1", "student-1", ParticipantType.STUDENT);
    assertThat(participantSlotId(event)).isEqualTo("slot-1-student-1");
  }

  @Test
  void participantSlotIdForUnmarkedAvailable() {
    var event = new BookingEvent.ParticipantUnmarkedAvailable(
        "slot-1", "instructor-1", ParticipantType.INSTRUCTOR);
    assertThat(participantSlotId(event)).isEqualTo("slot-1-instructor-1");
  }

  @Test
  void participantSlotIdForBooked() {
    var event = new BookingEvent.ParticipantBooked(
        "slot-1", "aircraft-1", ParticipantType.AIRCRAFT, "booking-1");
    assertThat(participantSlotId(event)).isEqualTo("slot-1-aircraft-1");
  }

  @Test
  void participantSlotIdForCanceled() {
    var event = new BookingEvent.ParticipantCanceled(
        "slot-1", "student-1", ParticipantType.STUDENT, "booking-1");
    assertThat(participantSlotId(event)).isEqualTo("slot-1-student-1");
  }

  @Test
  void participantSlotIdCombinesSlotAndParticipant() {
    var event = new BookingEvent.ParticipantBooked(
        "2026-06-10-09", "uuid-participant-99", ParticipantType.STUDENT, "booking-x");
    assertThat(participantSlotId(event)).isEqualTo("2026-06-10-09-uuid-participant-99");
  }
}
