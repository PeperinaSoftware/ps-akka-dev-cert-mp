package io.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "flight-conditions-agent")
public class FlightConditionsAgent extends Agent {

    private static final Logger logger = LoggerFactory.getLogger(FlightConditionsAgent.class);

    public record ConditionsReport(String timeSlotId, boolean meetsRequirements) {}

    private static final String SYSTEM_MESSAGE = """
            You are a flight conditions evaluator. Respond ONLY with a valid JSON object. No explanation, no preamble, no markdown.

            VFR minimum requirements:
            - Visibility: at least 3 statute miles
            - Ceiling: at least 1500 feet AGL
            - Wind speed: less than 25 knots
            - No active thunderstorms within 20 miles
            - No icing conditions

            Steps:
            1. Call getWeatherForecast with the timeSlotId.
            2. If the forecast says the slot is too far in the future: meetsRequirements = true.
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
    String getWeatherForecast(String timeSlotId) {
        try {
            int hour = Integer.parseInt(timeSlotId.substring(timeSlotId.lastIndexOf('-') + 1));

            // Daytime hours (06:00-18:00) simulate good VFR conditions.
            // Nighttime hours simulate poor conditions below VFR minimums.
            // Slots beyond forecast range are handled by the agent via system prompt.
            String forecast;
            if (hour >= 6 && hour <= 18) {
                forecast = String.format(
                        "Forecast for %s: Clear skies, Visibility 10 miles, Ceiling 5000 feet AGL, " +
                        "Wind 8 knots, No thunderstorms, No icing. Conditions are excellent for VFR flight.",
                        timeSlotId);
            } else {
                forecast = String.format(
                        "Forecast for %s: Overcast, Visibility 1 mile in fog, Ceiling 800 feet AGL, " +
                        "Wind 30 knots gusting 45, Thunderstorms in area, Icing conditions present. " +
                        "Conditions are below VFR minimums.",
                        timeSlotId);
            }
            logger.info("Weather forecast tool called for slot {} (hour={}): {}", timeSlotId, hour, forecast);
            return forecast;
        } catch (Exception e) {
            logger.warn("Could not parse slot ID for weather forecast: {}", timeSlotId);
            return "Unable to parse slot ID: " + timeSlotId + ". Cannot retrieve forecast.";
        }
    }
}
