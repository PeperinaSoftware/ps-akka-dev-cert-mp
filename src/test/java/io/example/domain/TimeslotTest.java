package io.example.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.example.domain.Participant.ParticipantType;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

public class TimeslotTest {

  private Timeslot emptySlot() {
    return new Timeslot(HashSet.newHashSet(10), HashSet.newHashSet(10));
  }

  @Test
  void reserveAddsParticipantToAvailable() {
    var slot = emptySlot();
    var event =
        new BookingEvent.ParticipantMarkedAvailable("slot-1", "student-1", ParticipantType.STUDENT);
    var updated = slot.reserve(event);
    assertThat(updated.isWaiting("student-1", ParticipantType.STUDENT)).isTrue();
  }

  @Test
  void unreserveRemovesParticipantFromAvailable() {
    var slot = emptySlot();
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "slot-1", "student-1", ParticipantType.STUDENT));
    slot =
        slot.unreserve(
            new BookingEvent.ParticipantUnmarkedAvailable(
                "slot-1", "student-1", ParticipantType.STUDENT));
    assertThat(slot.isWaiting("student-1", ParticipantType.STUDENT)).isFalse();
  }

  @Test
  void isBookableRequiresAllThreeParticipants() {
    var slot = emptySlot();
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "slot-1", "student-1", ParticipantType.STUDENT));
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "slot-1", "aircraft-1", ParticipantType.AIRCRAFT));

    assertThat(slot.isBookable("student-1", "aircraft-1", "instructor-1")).isFalse();

    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "slot-1", "instructor-1", ParticipantType.INSTRUCTOR));

    assertThat(slot.isBookable("student-1", "aircraft-1", "instructor-1")).isTrue();
  }

  @Test
  void bookMovesParticipantFromAvailableToBookings() {
    var slot = emptySlot();
    slot =
        slot.reserve(
            new BookingEvent.ParticipantMarkedAvailable(
                "slot-1", "student-1", ParticipantType.STUDENT));
    slot =
        slot.book(
            new BookingEvent.ParticipantBooked(
                "slot-1", "student-1", ParticipantType.STUDENT, "booking-1"));

    assertThat(slot.isWaiting("student-1", ParticipantType.STUDENT)).isFalse();
    assertThat(slot.findBooking("booking-1")).hasSize(1);
  }

  @Test
  void findBookingReturnsEmptyWhenNotFound() {
    var slot = emptySlot();
    assertThat(slot.findBooking("non-existent")).isEmpty();
  }

  @Test
  void findBookingReturnsThreeEntriesAfterFullBooking() {
    var slot = emptySlot();
    slot =
        slot.reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "student-1", ParticipantType.STUDENT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "student-1", ParticipantType.STUDENT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR, "booking-1"));

    assertThat(slot.findBooking("booking-1")).hasSize(3);
  }

  @Test
  void cancelBookingRemovesAllThreeEntries() {
    var slot = emptySlot();
    slot =
        slot.reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "student-1", ParticipantType.STUDENT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "student-1", ParticipantType.STUDENT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR, "booking-1"));

    slot = slot.cancelBooking("booking-1");
    assertThat(slot.findBooking("booking-1")).isEmpty();
  }

  @Test
  void cancelBookingOnlyRemovesTargetBooking() {
    var slot = emptySlot();
    slot =
        slot.reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "student-1", ParticipantType.STUDENT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT))
            .reserve(
                new BookingEvent.ParticipantMarkedAvailable(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "student-1", ParticipantType.STUDENT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "aircraft-1", ParticipantType.AIRCRAFT, "booking-1"))
            .book(
                new BookingEvent.ParticipantBooked(
                    "slot-1", "instructor-1", ParticipantType.INSTRUCTOR, "booking-1"));

    slot = slot.cancelBooking("non-existent");
    assertThat(slot.findBooking("booking-1")).hasSize(3);
  }
}
