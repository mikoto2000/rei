package dev.mikoto2000.rei.agent.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentProgressPropertiesTest {

  @Test
  void defaultValues() {
    AgentProgressProperties properties = new AgentProgressProperties();

    assertTrue(properties.isEnabled());
    assertEquals(3, properties.getMaxNoProgressIterations());
  }

  @Test
  void maxNoProgressIterationsIsAtLeastOne() {
    AgentProgressProperties properties = new AgentProgressProperties();

    properties.setMaxNoProgressIterations(0);

    assertEquals(1, properties.getMaxNoProgressIterations());
  }
}
