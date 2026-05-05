package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-slot")
public class BookingSlotEntity extends EventSourcedEntity<Timeslot, BookingEvent> {

    private final String entityId;
    private static final Logger logger = LoggerFactory.getLogger(BookingSlotEntity.class);

    public BookingSlotEntity(EventSourcedEntityContext context) {
        this.entityId = context.entityId();
    }

    public Effect<Done> markSlotAvailable(Command.MarkSlotAvailable cmd) {
        var event = new BookingEvent.ParticipantMarkedAvailable(
                entityId, cmd.participant().id(), cmd.participant().participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    public Effect<Done> unmarkSlotAvailable(Command.UnmarkSlotAvailable cmd) {
        var event = new BookingEvent.ParticipantUnmarkedAvailable(
                entityId, cmd.participant().id(), cmd.participant().participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    // NOTE: booking a slot should produce 3
    // `ParticipantBooked` events
    public Effect<Done> bookSlot(Command.BookReservation cmd) {
        if (!currentState().isBookable(cmd.studentId(), cmd.aircraftId(), cmd.instructorId())) {
            return effects().error("not all participants available for slot");
        }
        return effects().persist(
                new BookingEvent.ParticipantBooked(entityId, cmd.studentId(), ParticipantType.STUDENT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.aircraftId(), ParticipantType.AIRCRAFT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.instructorId(), ParticipantType.INSTRUCTOR, cmd.bookingId()))
                .thenReply(__ -> Done.done());
    }

    // NOTE: canceling a booking should produce 3
    // `ParticipantCanceled` events
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
                new BookingEvent.ParticipantCanceled(entityId, b0.participant().id(), b0.participant().participantType(), bookingId),
                new BookingEvent.ParticipantCanceled(entityId, b1.participant().id(), b1.participant().participantType(), bookingId),
                new BookingEvent.ParticipantCanceled(entityId, b2.participant().id(), b2.participant().participantType(), bookingId))
                .thenReply(__ -> Done.done());
    }

    public ReadOnlyEffect<Timeslot> getSlot() {
        return effects().reply(currentState());
    }

    @Override
    public Timeslot emptyState() {
        return new Timeslot(
                HashSet.newHashSet(10), HashSet.newHashSet(10));
    }

    @Override
    public Timeslot applyEvent(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantMarkedAvailable e -> currentState().reserve(e);
            case BookingEvent.ParticipantUnmarkedAvailable e -> currentState().unreserve(e);
            case BookingEvent.ParticipantBooked e -> currentState().book(e);
            case BookingEvent.ParticipantCanceled e -> currentState().cancelBooking(e.bookingId());
        };
    }

    public sealed interface Command {
        record MarkSlotAvailable(Participant participant) implements Command {
        }

        record UnmarkSlotAvailable(Participant participant) implements Command {
        }

        record BookReservation(
                String studentId, String aircraftId, String instructorId, String bookingId)
                implements Command {
        }
    }
}
