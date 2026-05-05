package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.example.domain.Participant.ParticipantType;

@Component(id = "participant-slot")
public class ParticipantSlotEntity
                extends EventSourcedEntity<ParticipantSlotEntity.State, ParticipantSlotEntity.Event> {

        private final String entityId;

        public ParticipantSlotEntity(EventSourcedEntityContext context) {
                this.entityId = context.entityId();
        }

        public Effect<Done> markAvailable(Commands.MarkAvailable cmd) {
                var event = new Event.MarkedAvailable(cmd.slotId(), cmd.participantId(), cmd.participantType());
                return effects().persist(event).thenReply(__ -> Done.done());
        }

        public Effect<Done> unmarkAvailable(Commands.UnmarkAvailable cmd) {
                var event = new Event.UnmarkedAvailable(cmd.slotId(), cmd.participantId(), cmd.participantType());
                return effects().persist(event).thenReply(__ -> Done.done());
        }

        public Effect<Done> book(Commands.Book cmd) {
                var event = new Event.Booked(cmd.slotId(), cmd.participantId(), cmd.participantType(), cmd.bookingId());
                return effects().persist(event).thenReply(__ -> Done.done());
        }

        public Effect<Done> cancel(Commands.Cancel cmd) {
                var event = new Event.Canceled(cmd.slotId(), cmd.participantId(), cmd.participantType(), cmd.bookingId());
                return effects().persist(event).thenReply(__ -> Done.done());
        }

        @Override
        public State emptyState() {
                return new State("", "", null, "");
        }

        @Override
        public State applyEvent(Event event) {
                return switch (event) {
                        case Event.MarkedAvailable e -> new State(e.slotId(), e.participantId(), e.participantType(), "available");
                        case Event.UnmarkedAvailable e -> new State(e.slotId(), e.participantId(), e.participantType(), "removed");
                        case Event.Booked e -> new State(e.slotId(), e.participantId(), e.participantType(), "booked");
                        case Event.Canceled e -> new State(e.slotId(), e.participantId(), e.participantType(), "canceled");
                };
        }

        record State(
                        String slotId, String participantId, ParticipantType participantType, String status) {
        }

        public sealed interface Commands {
                record MarkAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Commands {
                }

                record UnmarkAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Commands {
                }

                record Book(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Commands {
                }

                record Cancel(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Commands {
                }
        }

        public sealed interface Event {
                @TypeName("marked-available")
                record MarkedAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Event {
                }

                @TypeName("unmarked-available")
                record UnmarkedAvailable(String slotId, String participantId, ParticipantType participantType)
                                implements Event {
                }

                @TypeName("participant-booked")
                record Booked(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Event {
                }

                @TypeName("participant-canceled")
                record Canceled(
                                String slotId, String participantId, ParticipantType participantType, String bookingId)
                                implements Event {
                }
        }
}
