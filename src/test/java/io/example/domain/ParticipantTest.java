package io.example.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

public class ParticipantTest {

  @Test
  void participantHoldsIdAndType() {
    var p = new Participant("student-1", ParticipantType.STUDENT);
    assertThat(p.id()).isEqualTo("student-1");
    assertThat(p.participantType()).isEqualTo(ParticipantType.STUDENT);
  }

  @Test
  void participantEqualityBasedOnIdAndType() {
    var p1 = new Participant("student-1", ParticipantType.STUDENT);
    var p2 = new Participant("student-1", ParticipantType.STUDENT);
    assertThat(p1).isEqualTo(p2);
  }

  @Test
  void participantsWithDifferentTypesAreNotEqual() {
    var student = new Participant("id-1", ParticipantType.STUDENT);
    var instructor = new Participant("id-1", ParticipantType.INSTRUCTOR);
    assertThat(student).isNotEqualTo(instructor);
  }

  @Test
  void participantTypeEnumHasThreeValues() {
    assertThat(ParticipantType.values()).containsExactlyInAnyOrder(
        ParticipantType.STUDENT,
        ParticipantType.INSTRUCTOR,
        ParticipantType.AIRCRAFT);
  }

  @Test
  void participantTypeValueOfWorks() {
    assertThat(ParticipantType.valueOf("STUDENT")).isEqualTo(ParticipantType.STUDENT);
    assertThat(ParticipantType.valueOf("INSTRUCTOR")).isEqualTo(ParticipantType.INSTRUCTOR);
    assertThat(ParticipantType.valueOf("AIRCRAFT")).isEqualTo(ParticipantType.AIRCRAFT);
  }
}
