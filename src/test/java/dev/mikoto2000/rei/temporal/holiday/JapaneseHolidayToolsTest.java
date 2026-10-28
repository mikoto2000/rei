package dev.mikoto2000.rei.temporal.holiday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class JapaneseHolidayToolsTest {
  @TempDir Path directory;

  Map<String, org.springframework.ai.tool.ToolCallback> callbacks(JapaneseHolidayService service) {
    return Arrays.stream(
            MethodToolCallbackProvider.builder()
                .toolObjects(new JapaneseHolidayTools(service))
                .build()
                .getToolCallbacks())
        .collect(
            Collectors.toMap(
                callback -> callback.getToolDefinition().name(), callback -> callback));
  }

  @Test
  void registersTypedReadOnlyToolsAndConvertsJsonArguments() throws Exception {
    Path csv =
        java.nio.file.Files.writeString(
            directory.resolve("syukujitsu.csv"),
            HolidayCsvTest.HEADER + "\n2026/11/23,勤労感謝の日\n",
            HolidayCsvTest.MS932);
    var callbacks = callbacks(new JapaneseHolidayService(csv, Set.of(2026), Clock.systemUTC()));
    assertEquals(Set.of("getHolidayInfo", "listHolidays"), callbacks.keySet());
    String result = callbacks.get("getHolidayInfo").call("{\"date\":\"2026-11-23\"}");
    assertTrue(result.contains("勤労感謝の日"));
    assertTrue(result.contains("HOLIDAY"));
    var json = new tools.jackson.databind.ObjectMapper().readTree(result);
    assertEquals("MONDAY", json.get("dayOfWeek").asText());
    assertFalse(json.get("isWeekend").asBoolean());
    assertTrue(json.get("isNationalHolidayOrDayOff").asBoolean());
    var unknown = new tools.jackson.databind.ObjectMapper().readTree(
        callbacks.get("getHolidayInfo").call("{\"date\":\"2028-05-03\"}"));
    assertTrue(unknown.get("isNationalHolidayOrDayOff").isNull());
    assertFalse(result.contains(directory.toString()));
    assertTrue(
        callbacks.get("getHolidayInfo").call("{\"date\":\"2026-11-24\"}").contains("NOT_HOLIDAY"));
    assertTrue(
        callbacks.get("getHolidayInfo").call("{\"date\":\"2028-05-03\"}").contains("UNKNOWN"));
    String list =
        callbacks.get("listHolidays").call("{\"from\":\"2026-11-01\",\"to\":\"2027-01-01\"}");
    assertTrue(list.contains("勤労感謝の日"));
    assertTrue(list.contains("unknownPeriods"));
    for (String name : callbacks.keySet()) {
      assertEquals(
          Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),
          dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities(name));
    }
  }

  @Test
  void returnsUnknownAndSafeInputErrors() {
    var callbacks =
        callbacks(
            new JapaneseHolidayService(
                directory.resolve("private.csv"), Set.of(), Clock.systemUTC()));
    assertTrue(
        callbacks.get("getHolidayInfo").call("{\"date\":\"2026-11-23\"}").contains("UNKNOWN"));
    for (String date : new String[] {"2026-02-30", "2026/11/23", "", "2026-1-1"}) {
      String response = callbacks.get("getHolidayInfo").call("{\"date\":\"" + date + "\"}");
      assertTrue(response.contains("INVALID_ARGUMENT"));
      assertFalse(response.contains("private.csv"));
      assertFalse(response.contains("Exception"));
    }
    assertTrue(
        callbacks.get("getHolidayInfo").call("{\"date\":null}").contains("INVALID_ARGUMENT"));
    for (String input :
        new String[] {
          "{\"from\":\"2026-11-23\",\"to\":\"2026-11-22\"}",
          "{\"from\":\"2026-01-01\",\"to\":\"2027-12-31\"}"
        }) {
      assertTrue(callbacks.get("listHolidays").call(input).contains("INVALID_ARGUMENT"));
    }
  }

  @Test
  void sanitizesUnexpectedServiceFailures() {
    var service = org.mockito.Mockito.mock(JapaneseHolidayService.class);
    org.mockito.Mockito.when(service.getHolidayInfo(LocalDate.of(2026, 11, 23)))
        .thenThrow(new IllegalStateException("secret path"));
    String response = callbacks(service).get("getHolidayInfo").call("{\"date\":\"2026-11-23\"}");
    assertTrue(response.contains("DATA_UNAVAILABLE"));
    assertFalse(response.contains("secret path"));
  }

  @Test
  void validatesExplicitCoverageConfiguration() {
    assertEquals(
        Set.of(2020, 2021, 2026, 2027), HolidayConfiguration.parseYears("2020,2021,2026-2027"));
    assertEquals(Set.of(), HolidayConfiguration.parseYears(""));
    for (String invalid : new String[] {"2027-2026", "2026,", "0", "2026-9999", "2026/2027"})
      assertThrows(IllegalArgumentException.class, () -> HolidayConfiguration.parseYears(invalid));
  }
}
