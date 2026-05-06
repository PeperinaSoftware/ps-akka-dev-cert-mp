package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class FlightConditionsAgentTest {

  // Direct instantiation is not possible because Agent extends a framework class.
  // These tests verify the weather forecast logic via package-private access.

  private final FlightConditionsAgent agent = new FlightConditionsAgent();

  @Test
  void forecastFarFutureSlotReturnsUnavailableMessage() {
    // Slot 10+ years in future — always beyond 10-day window
    String result = agent.getWeatherForecast("2099-06-15-10");
    assertThat(result).contains("more than 10 days in the future");
  }

  @Test
  void forecastEvenDayReturnsGoodConditions() {
    // Use a date with even day that is in the past or near future
    // We use 2026-01-02-10 (day=2, even) — always past, but logic still executes
    String result = agent.getWeatherForecast("2026-01-02-10");
    assertThat(result).contains("Visibility 10 miles");
    assertThat(result).contains("Conditions are excellent for VFR flight");
  }

  @Test
  void forecastOddDayReturnsPoorConditions() {
    // Use a date with odd day
    String result = agent.getWeatherForecast("2026-01-03-10");
    assertThat(result).contains("Visibility 1 mile in fog");
    assertThat(result).contains("below VFR minimums");
  }

  @Test
  void forecastInvalidSlotIdReturnsErrorMessage() {
    String result = agent.getWeatherForecast("invalid-slot-id");
    assertThat(result).contains("Unable to parse slot ID");
  }

  @Test
  void conditionsReportRecordHoldsCorrectValues() {
    var report = new FlightConditionsAgent.ConditionsReport("2026-06-10-09", true);
    assertThat(report.timeSlotId()).isEqualTo("2026-06-10-09");
    assertThat(report.meetsRequirements()).isTrue();
  }

  @Test
  void conditionsReportFalseWhenConditionsFail() {
    var report = new FlightConditionsAgent.ConditionsReport("2026-06-10-09", false);
    assertThat(report.meetsRequirements()).isFalse();
  }
}
