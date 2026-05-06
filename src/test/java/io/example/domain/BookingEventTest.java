package io.example.domain;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.annotations.TypeName;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class BookingEventTest {

  @Test
  void participantMarkedAvailableHoldsCorrectFields() {
    var event =
        new BookingEvent.ParticipantMarkedAvailable("slot-1", "student-1", ParticipantType.STUDENT);
    assertThat(event.slotId()).isEqualTo("slot-1");
    assertThat(event.participantId()).isEqualTo("student-1");
    assertThat(event.participantType()).isEqualTo(ParticipantType.STUDENT);
  }

  @Test
  void participantUnmarkedAvailableHoldsCorrectFields() {
    var event =
        new BookingEvent.ParticipantUnmarkedAvailable(
            "slot-1", "student-1", ParticipantType.STUDENT);
    assertThat(event.slotId()).isEqualTo("slot-1");
    assertThat(event.participantId()).isEqualTo("student-1");
  }

  @Test
  void participantBookedHoldsCorrectFields() {
    var event =
        new BookingEvent.ParticipantBooked(
            "slot-1", "student-1", ParticipantType.STUDENT, "booking-1");
    assertThat(event.slotId()).isEqualTo("slot-1");
    assertThat(event.participantId()).isEqualTo("student-1");
    assertThat(event.bookingId()).isEqualTo("booking-1");
  }

  @Test
  void participantCanceledHoldsCorrectFields() {
    var event =
        new BookingEvent.ParticipantCanceled(
            "slot-1", "student-1", ParticipantType.STUDENT, "booking-1");
    assertThat(event.slotId()).isEqualTo("slot-1");
    assertThat(event.bookingId()).isEqualTo("booking-1");
  }

  @Test
  void allEventsHaveTypeNameAnnotation() throws NoSuchFieldException {
    assertThat(
            BookingEvent.ParticipantMarkedAvailable.class.isAnnotationPresent(TypeName.class))
        .isTrue();
    assertThat(
            BookingEvent.ParticipantUnmarkedAvailable.class.isAnnotationPresent(TypeName.class))
        .isTrue();
    assertThat(BookingEvent.ParticipantBooked.class.isAnnotationPresent(TypeName.class)).isTrue();
    assertThat(BookingEvent.ParticipantCanceled.class.isAnnotationPresent(TypeName.class)).isTrue();
  }

  @Test
  void typeNamesAreStableAndUnique() {
    var n1 =
        BookingEvent.ParticipantMarkedAvailable.class.getAnnotation(TypeName.class).value();
    var n2 =
        BookingEvent.ParticipantUnmarkedAvailable.class.getAnnotation(TypeName.class).value();
    var n3 = BookingEvent.ParticipantBooked.class.getAnnotation(TypeName.class).value();
    var n4 = BookingEvent.ParticipantCanceled.class.getAnnotation(TypeName.class).value();

    assertThat(java.util.Set.of(n1, n2, n3, n4)).hasSize(4);
    assertThat(n1).isEqualTo("slot-reserved");
    assertThat(n2).isEqualTo("slot-unreserved");
    assertThat(n3).isEqualTo("reservation-booked");
    assertThat(n4).isEqualTo("booking-participant-canceled");
  }
}
