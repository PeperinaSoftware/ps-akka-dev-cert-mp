package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class BookingSlotEntityTest {

  @Test
  void emptyStateIsNotNull() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);
    assertThat(testKit.getState()).isNotNull();
  }

  @Test
  void cancelBookingNotFoundReturnsError() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);
    var cmd = new BookingSlotEntity.Command.CancelBooking("non-existent-booking");
    var result = testKit.call(e -> e.cancelBooking(cmd));
    assertThat(result.isError()).isTrue();
    assertThat(result.getError()).contains("booking not found");
  }

  @Test
  void markSlotAvailableEmitsEvent() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);
    var cmd =
        new BookingSlotEntity.Command.MarkSlotAvailable(
            new Participant("student-1", ParticipantType.STUDENT));
    var result = testKit.call(e -> e.markSlotAvailable(cmd));
    assertThat(result.isError()).isFalse();
    assertThat(result.getAllEvents()).hasSize(1);
    assertThat(result.getNextEventOfType(BookingEvent.ParticipantMarkedAvailable.class))
        .isNotNull();
  }

  @Test
  void bookSlotEmitsThreeEvents() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);

    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("student-1", ParticipantType.STUDENT))));
    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("aircraft-1", ParticipantType.AIRCRAFT))));
    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("instructor-1", ParticipantType.INSTRUCTOR))));

    var bookCmd =
        new BookingSlotEntity.Command.BookReservation(
            "student-1", "aircraft-1", "instructor-1", "booking-1");
    var result = testKit.call(e -> e.bookSlot(bookCmd));

    assertThat(result.isError()).isFalse();
    assertThat(result.getAllEvents()).hasSize(3);
    result
        .getAllEvents()
        .forEach(event -> assertThat(event).isInstanceOf(BookingEvent.ParticipantBooked.class));
  }

  @Test
  void cancelBookingEmitsThreeEvents() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);

    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("student-1", ParticipantType.STUDENT))));
    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("aircraft-1", ParticipantType.AIRCRAFT))));
    testKit.call(
        e ->
            e.markSlotAvailable(
                new BookingSlotEntity.Command.MarkSlotAvailable(
                    new Participant("instructor-1", ParticipantType.INSTRUCTOR))));
    testKit.call(
        e ->
            e.bookSlot(
                new BookingSlotEntity.Command.BookReservation(
                    "student-1", "aircraft-1", "instructor-1", "booking-1")));

    var result = testKit.call(e -> e.cancelBooking(new BookingSlotEntity.Command.CancelBooking("booking-1")));

    assertThat(result.isError()).isFalse();
    assertThat(result.getAllEvents()).hasSize(3);
    result
        .getAllEvents()
        .forEach(event -> assertThat(event).isInstanceOf(BookingEvent.ParticipantCanceled.class));
  }

  @Test
  void bookSlotFailsWhenParticipantNotAvailable() {
    var testKit = EventSourcedTestKit.of(BookingSlotEntity::new);
    var bookCmd =
        new BookingSlotEntity.Command.BookReservation(
            "student-1", "aircraft-1", "instructor-1", "booking-1");
    var result = testKit.call(e -> e.bookSlot(bookCmd));
    assertThat(result.isError()).isTrue();
    assertThat(result.getError()).contains("not all participants available");
  }
}
