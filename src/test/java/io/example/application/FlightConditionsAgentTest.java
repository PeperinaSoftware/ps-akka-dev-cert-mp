package io.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class FlightConditionsAgentTest {

  // Direct instantiation is not possible because Agent extends a framework class.
  // These tests verify the weather forecast logic via package-private access.

  private final FlightConditionsAgent agent = new FlightConditionsAgent();

  @Test
  void forecastDaytimeHourReturnsGoodConditions() {
    // Hour 10 (daytime: 06-18) → clear skies, VFR-friendly
    String result = agent.getWeatherForecast("2026-08-10-10");
    assertThat(result).contains("Visibility 10 miles");
    assertThat(result).contains("Conditions are excellent for VFR flight");
  }

  @Test
  void forecastNighttimeHourReturnsPoorConditions() {
    // Hour 23 (nighttime: outside 06-18) → fog, low ceiling, below VFR minimums
    String result = agent.getWeatherForecast("2026-08-11-23");
    assertThat(result).contains("Visibility 1 mile in fog");
    assertThat(result).contains("below VFR minimums");
  }

  @Test
  void forecastBoundaryHour6ReturnsGoodConditions() {
    // Hour 6 is the lower boundary of daytime — should return good conditions
    String result = agent.getWeatherForecast("2026-08-10-06");
    assertThat(result).contains("Conditions are excellent for VFR flight");
  }

  @Test
  void forecastBoundaryHour5ReturnsPoorConditions() {
    // Hour 5 is just before daytime window — should return poor conditions
    String result = agent.getWeatherForecast("2026-08-10-05");
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
