package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

public class ParticipantSlotsViewTest {

  @Test
  void slotRowHoldsAllFields() {
    var row = new ParticipantSlotsView.SlotRow(
        "slot-1", "student-1", "STUDENT", "booking-1", "booked");
    assertThat(row.slotId()).isEqualTo("slot-1");
    assertThat(row.participantId()).isEqualTo("student-1");
    assertThat(row.participantType()).isEqualTo("STUDENT");
    assertThat(row.bookingId()).isEqualTo("booking-1");
    assertThat(row.status()).isEqualTo("booked");
  }

  @Test
  void slotRowAvailableHasEmptyBookingId() {
    var row = new ParticipantSlotsView.SlotRow(
        "slot-1", "instructor-1", "INSTRUCTOR", "", "available");
    assertThat(row.bookingId()).isEmpty();
    assertThat(row.status()).isEqualTo("available");
  }

  @Test
  void slotRowCanceledHasCorrectStatus() {
    var row = new ParticipantSlotsView.SlotRow(
        "slot-1", "aircraft-1", "AIRCRAFT", "booking-1", "canceled");
    assertThat(row.status()).isEqualTo("canceled");
    assertThat(row.bookingId()).isEqualTo("booking-1");
  }

  @Test
  void participantStatusInputHoldsFields() {
    var input = new ParticipantSlotsView.ParticipantStatusInput("student-1", "booked");
    assertThat(input.participantId()).isEqualTo("student-1");
    assertThat(input.status()).isEqualTo("booked");
  }

  @Test
  void slotListWrapsRows() {
    var rows = List.of(
        new ParticipantSlotsView.SlotRow("slot-1", "student-1", "STUDENT", "booking-1", "booked"),
        new ParticipantSlotsView.SlotRow("slot-1", "aircraft-1", "AIRCRAFT", "booking-1", "booked")
    );
    var slotList = new ParticipantSlotsView.SlotList(rows);
    assertThat(slotList.slots()).hasSize(2);
  }
}
