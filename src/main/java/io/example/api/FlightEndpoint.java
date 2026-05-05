package io.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.example.application.BookingSlotEntity;
import io.example.application.ParticipantSlotsView;
import io.example.application.ParticipantSlotsView.SlotList;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;
import io.example.application.FlightConditionsAgent;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/flight")
public class FlightEndpoint extends AbstractHttpEndpoint {
    private final Logger log = LoggerFactory.getLogger(FlightEndpoint.class);

    private final ComponentClient componentClient;

    public FlightEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    @Post("/bookings/{slotId}")
    public CompletionStage<HttpResponse> createBooking(String slotId, BookingRequest request) {
        log.info("Creating booking for slot {}: {}", slotId, request);

        if (!isFutureSlot(slotId)) {
            throw HttpException.badRequest("slot is not in the future");
        }

        String sessionId = slotId + "-" + request.bookingId();
        return componentClient.forAgent()
                .inSession(sessionId)
                .method(FlightConditionsAgent::query)
                .invokeAsync(slotId)
                .thenCompose(report -> {
                    if (!report.meetsRequirements()) {
                        return CompletableFuture.failedFuture(
                                HttpException.badRequest("flight conditions not approved for slot " + slotId));
                    }
                    var cmd = new BookingSlotEntity.Command.BookReservation(
                            request.studentId(), request.aircraftId(), request.instructorId(), request.bookingId());
                    return componentClient.forEventSourcedEntity(slotId)
                            .method(BookingSlotEntity::bookSlot)
                            .invokeAsync(cmd)
                            .thenApply(__ -> HttpResponses.created());
                });
    }

    @Delete("/bookings/{slotId}/{bookingId}")
    public CompletionStage<HttpResponse> cancelBooking(String slotId, String bookingId) {
        log.info("Canceling booking {} for slot {}", bookingId, slotId);
        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::cancelBooking)
                .invokeAsync(bookingId)
                .thenApply(__ -> HttpResponses.ok());
    }

    @Get("/slots/{participantId}/{status}")
    public CompletionStage<SlotList> slotsByStatus(String participantId, String status) {
        return componentClient.forView()
                .method(ParticipantSlotsView::getSlotsByParticipantAndStatus)
                .invokeAsync(new ParticipantSlotsView.ParticipantStatusInput(participantId, status));
    }

    @Get("/availability/{slotId}")
    public CompletionStage<Timeslot> getSlot(String slotId) {
        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::getSlot)
                .invokeAsync();
    }

    @Post("/availability/{slotId}")
    public CompletionStage<HttpResponse> markAvailable(String slotId, AvailabilityRequest request) {
        ParticipantType participantType;
        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Bad participant type {}", request.participantType());
            throw HttpException.badRequest("invalid participant type");
        }
        log.info("Marking timeslot available for entity {}", slotId);
        var cmd = new BookingSlotEntity.Command.MarkSlotAvailable(new Participant(request.participantId(), participantType));
        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::markSlotAvailable)
                .invokeAsync(cmd)
                .thenApply(__ -> HttpResponses.ok());
    }

    @Delete("/availability/{slotId}")
    public CompletionStage<HttpResponse> unmarkAvailable(String slotId, AvailabilityRequest request) {
        ParticipantType participantType;
        try {
            participantType = ParticipantType.valueOf(request.participantType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Bad participant type {}", request.participantType());
            throw HttpException.badRequest("invalid participant type");
        }
        var cmd = new BookingSlotEntity.Command.UnmarkSlotAvailable(new Participant(request.participantId(), participantType));
        return componentClient.forEventSourcedEntity(slotId)
                .method(BookingSlotEntity::unmarkSlotAvailable)
                .invokeAsync(cmd)
                .thenApply(__ -> HttpResponses.ok());
    }

    private boolean isFutureSlot(String slotId) {
        try {
            String[] parts = slotId.split("-");
            LocalDateTime slotTime = LocalDateTime.of(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), 0);
            // Slot IDs and server time are both treated as UTC.
            // Clients must submit slotId values in UTC format (YYYY-MM-DD-HH).
            return slotTime.isAfter(LocalDateTime.now());
        } catch (Exception e) {
            throw HttpException.badRequest("invalid slot ID format, expected YYYY-MM-DD-HH");
        }
    }

    public record BookingRequest(
            String studentId, String aircraftId, String instructorId, String bookingId) {
    }

    public record AvailabilityRequest(String participantId, String participantType) {
    }
}
