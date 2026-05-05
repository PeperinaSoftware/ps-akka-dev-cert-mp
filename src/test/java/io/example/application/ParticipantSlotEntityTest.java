package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class ParticipantSlotEntityTest {

  @Test
  void emptyStateIsNotNull() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    assertThat(testKit.getState()).isNotNull();
    assertThat(testKit.getState().status()).isEmpty();
  }

  @Test
  void markAvailableSetsStatusAvailable() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    var cmd =
        new ParticipantSlotEntity.Commands.MarkAvailable(
            "slot-1", "participant-1", ParticipantType.STUDENT);
    testKit.call(e -> e.markAvailable(cmd));

    assertThat(testKit.getState().status()).isEqualTo("available");
    assertThat(testKit.getState().slotId()).isEqualTo("slot-1");
    assertThat(testKit.getState().participantId()).isEqualTo("participant-1");
  }

  @Test
  void unmarkAvailableSetsStatusRemoved() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    testKit.call(
        e ->
            e.markAvailable(
                new ParticipantSlotEntity.Commands.MarkAvailable(
                    "slot-1", "participant-1", ParticipantType.STUDENT)));
    testKit.call(
        e ->
            e.unmarkAvailable(
                new ParticipantSlotEntity.Commands.UnmarkAvailable(
                    "slot-1", "participant-1", ParticipantType.STUDENT)));

    assertThat(testKit.getState().status()).isEqualTo("removed");
  }

  @Test
  void bookSetsStatusBooked() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    testKit.call(
        e ->
            e.markAvailable(
                new ParticipantSlotEntity.Commands.MarkAvailable(
                    "slot-1", "participant-1", ParticipantType.STUDENT)));
    testKit.call(
        e ->
            e.book(
                new ParticipantSlotEntity.Commands.Book(
                    "slot-1", "participant-1", ParticipantType.STUDENT, "booking-1")));

    assertThat(testKit.getState().status()).isEqualTo("booked");
  }

  @Test
  void cancelSetsStatusCanceled() {
    var testKit = EventSourcedTestKit.of(ParticipantSlotEntity::new);
    testKit.call(
        e ->
            e.markAvailable(
                new ParticipantSlotEntity.Commands.MarkAvailable(
                    "slot-1", "participant-1", ParticipantType.STUDENT)));
    testKit.call(
        e ->
            e.book(
                new ParticipantSlotEntity.Commands.Book(
                    "slot-1", "participant-1", ParticipantType.STUDENT, "booking-1")));
    testKit.call(
        e ->
            e.cancel(
                new ParticipantSlotEntity.Commands.Cancel(
                    "slot-1", "participant-1", ParticipantType.STUDENT, "booking-1")));

    assertThat(testKit.getState().status()).isEqualTo("canceled");
  }
}
