package io.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.example.domain.BookingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This class is responsible for consuming events from the booking
// slot entity and turning those into command calls on the
// participant slot entity
@Component(id = "booking-slot-consumer")
@Consume.FromEventSourcedEntity(BookingSlotEntity.class)
public class SlotToParticipantConsumer extends Consumer {

    private final ComponentClient client;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public SlotToParticipantConsumer(ComponentClient client) {
        this.client = client;
    }

    public Effect onEvent(BookingEvent.ParticipantMarkedAvailable e) {
        logger.info("Marking available slot {} for participant {}", e.slotId(), e.participantId());
        var cmd = new ParticipantSlotEntity.Commands.MarkAvailable(e.slotId(), e.participantId(), e.participantType());
        return effects().asyncDone(
                client.forEventSourcedEntity(participantSlotId(e))
                        .method(ParticipantSlotEntity::markAvailable)
                        .invokeAsync(cmd));
    }

    public Effect onEvent(BookingEvent.ParticipantUnmarkedAvailable e) {
        logger.info("Unmarking available slot {} for participant {}", e.slotId(), e.participantId());
        var cmd = new ParticipantSlotEntity.Commands.UnmarkAvailable(e.slotId(), e.participantId(), e.participantType());
        return effects().asyncDone(
                client.forEventSourcedEntity(participantSlotId(e))
                        .method(ParticipantSlotEntity::unmarkAvailable)
                        .invokeAsync(cmd));
    }

    public Effect onEvent(BookingEvent.ParticipantBooked e) {
        logger.info("Booking slot {} for participant {}", e.slotId(), e.participantId());
        var cmd = new ParticipantSlotEntity.Commands.Book(e.slotId(), e.participantId(), e.participantType(), e.bookingId());
        return effects().asyncDone(
                client.forEventSourcedEntity(participantSlotId(e))
                        .method(ParticipantSlotEntity::book)
                        .invokeAsync(cmd));
    }

    public Effect onEvent(BookingEvent.ParticipantCanceled e) {
        logger.info("Canceling booking {} for participant {}", e.bookingId(), e.participantId());
        var cmd = new ParticipantSlotEntity.Commands.Cancel(e.slotId(), e.participantId(), e.participantType(), e.bookingId());
        return effects().asyncDone(
                client.forEventSourcedEntity(participantSlotId(e))
                        .method(ParticipantSlotEntity::cancel)
                        .invokeAsync(cmd));
    }

    // Participant slots are keyed by a derived key made up of
    // {slotId}-{participantId}
    // We don't need the participant type here because the participant IDs
    // should always be unique/UUIDs
    private String participantSlotId(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantBooked evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantUnmarkedAvailable evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantMarkedAvailable evt -> evt.slotId() + "-" + evt.participantId();
            case BookingEvent.ParticipantCanceled evt -> evt.slotId() + "-" + evt.participantId();
        };
    }

    private String participantSlotId(BookingEvent.ParticipantMarkedAvailable e) {
        return e.slotId() + "-" + e.participantId();
    }

    private String participantSlotId(BookingEvent.ParticipantUnmarkedAvailable e) {
        return e.slotId() + "-" + e.participantId();
    }

    private String participantSlotId(BookingEvent.ParticipantBooked e) {
        return e.slotId() + "-" + e.participantId();
    }

    private String participantSlotId(BookingEvent.ParticipantCanceled e) {
        return e.slotId() + "-" + e.participantId();
    }
}
