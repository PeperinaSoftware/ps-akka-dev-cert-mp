package io.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Component(id = "flight-conditions-agent")
public class FlightConditionsAgent extends Agent {

    public record ConditionsReport(String timeSlotId, boolean meetsRequirements) {}

    private static final String SYSTEM_MESSAGE = """
            You are a flight conditions evaluator. Respond ONLY with a valid JSON object. No explanation, no preamble, no markdown.

            VFR minimum requirements:
            - Visibility: at least 3 statute miles
            - Ceiling: at least 1000 feet AGL
            - Wind speed: less than 25 knots
            - No active thunderstorms within 20 miles
            - No icing conditions

            Steps:
            1. Call getWeatherForecast with the timeSlotId.
            2. If slot is more than 10 days in the future: meetsRequirements = true.
            3. Otherwise evaluate conditions against VFR minimums above.

            You MUST respond with ONLY this exact JSON structure, nothing else:
            {"timeSlotId": "<the slot id>", "meetsRequirements": <true or false>}
            """.stripIndent();

    public Effect<ConditionsReport> query(String timeSlotId) {
        return effects()
                .systemMessage(SYSTEM_MESSAGE)
                .userMessage("Evaluate flight conditions for time slot: " + timeSlotId)
                .responseAs(ConditionsReport.class)
                .thenReply();
    }

    @FunctionTool(description = "Queries the weather conditions as they are forecasted based on the time slot ID of the training session booking")
    private String getWeatherForecast(String timeSlotId) {
        try {
            String[] parts = timeSlotId.split("-");
            LocalDateTime slotTime = LocalDateTime.of(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), 0);

            long daysUntilSlot = ChronoUnit.DAYS.between(LocalDate.now(), slotTime.toLocalDate());

            if (daysUntilSlot > 10) {
                return "Forecast unavailable: slot is more than 10 days in the future. Conditional approval applies.";
            }

            // Simulate varied conditions: even day = good, odd day = poor
            int day = slotTime.getDayOfMonth();
            if (day % 2 == 0) {
                return String.format(
                        "Forecast for %s: Visibility 10 miles, Ceiling 3500 feet AGL, " +
                        "Wind 8 knots from 270, No thunderstorms, No icing. Conditions are excellent for VFR flight.",
                        timeSlotId);
            } else {
                return String.format(
                        "Forecast for %s: Visibility 1 mile in fog, Ceiling 300 feet AGL, " +
                        "Wind 35 knots gusting 45, Thunderstorms reported 5 miles northwest, Icing conditions present. " +
                        "Conditions are below VFR minimums.",
                        timeSlotId);
            }
        } catch (Exception e) {
            return "Unable to parse slot ID: " + timeSlotId + ". Cannot retrieve forecast.";
        }
    }
}
