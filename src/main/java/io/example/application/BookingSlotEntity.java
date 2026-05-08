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
        logger.info("Participant {} ({}) marked available for slot {}",
                cmd.participant().id(), cmd.participant().participantType(), entityId);
        var event = new BookingEvent.ParticipantMarkedAvailable(
                entityId, cmd.participant().id(), cmd.participant().participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    public Effect<Done> unmarkSlotAvailable(Command.UnmarkSlotAvailable cmd) {
        logger.info("Participant {} ({}) unmarked availability for slot {}",
                cmd.participant().id(), cmd.participant().participantType(), entityId);
        var event = new BookingEvent.ParticipantUnmarkedAvailable(
                entityId, cmd.participant().id(), cmd.participant().participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    // NOTE: booking a slot should produce 3
    // `ParticipantBooked` events
    public Effect<Done> bookSlot(Command.BookReservation cmd) {
        if (!currentState().isBookable(cmd.studentId(), cmd.aircraftId(), cmd.instructorId())) {
            logger.warn("Cannot book slot {}: not all participants available (student={}, aircraft={}, instructor={})",
                    entityId, cmd.studentId(), cmd.aircraftId(), cmd.instructorId());
            return effects().error("not all participants available for slot");
        }
        logger.info("Booking slot {} — student={}, aircraft={}, instructor={}, bookingId={}",
                entityId, cmd.studentId(), cmd.aircraftId(), cmd.instructorId(), cmd.bookingId());
        return effects().persist(
                new BookingEvent.ParticipantBooked(entityId, cmd.studentId(), ParticipantType.STUDENT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.aircraftId(), ParticipantType.AIRCRAFT, cmd.bookingId()),
                new BookingEvent.ParticipantBooked(entityId, cmd.instructorId(), ParticipantType.INSTRUCTOR, cmd.bookingId()))
                .thenReply(__ -> Done.done());
    }

    // NOTE: canceling a booking should produce 3
    // `ParticipantCanceled` events
    public Effect<Done> cancelBooking(Command.CancelBooking cmd) {
        var bookings = currentState().findBooking(cmd.bookingId());
        if (bookings.isEmpty()) {
            logger.warn("Booking {} not found in slot {}", cmd.bookingId(), entityId);
            return effects().error("booking not found: " + cmd.bookingId());
        }
        if (bookings.size() != 3) {
            logger.warn("Inconsistent booking state for {} in slot {} — expected 3 entries, found {}",
                    cmd.bookingId(), entityId, bookings.size());
            return effects().error("inconsistent booking state for: " + cmd.bookingId());
        }
        logger.info("Canceling booking {} from slot {}", cmd.bookingId(), entityId);
        var b0 = bookings.get(0);
        var b1 = bookings.get(1);
        var b2 = bookings.get(2);
        return effects().persist(
                new BookingEvent.ParticipantCanceled(entityId, b0.participant().id(), b0.participant().participantType(), cmd.bookingId()),
                new BookingEvent.ParticipantCanceled(entityId, b1.participant().id(), b1.participant().participantType(), cmd.bookingId()),
                new BookingEvent.ParticipantCanceled(entityId, b2.participant().id(), b2.participant().participantType(), cmd.bookingId()))
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

        record CancelBooking(String bookingId) implements Command {
        }
    }
}
