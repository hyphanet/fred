package freenet.client.async;

import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Arrays;

import org.hamcrest.Matchers;
import org.junit.Test;

public class HealingDecisionSupplierTest {

  @Test
  public void healingAlwaysAcceptsAKeyAtTheNodeLocation() {
    for (double i : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
      HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
          () -> 0.1,
          () -> true,
          () -> i);
      assertThat(
          String.format("Healing triggers at random value %g", i),
          healingDecisionSupplier.shouldHeal(0.1),
          Matchers.equalTo(true));
    }
  }

  @Test
  public void healingAccepts70PercentOfKeysInShortDistance() {
    for (double i : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4)) {
      HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
          () -> 0.1,
          () -> true,
          () -> i);
      assertThat(String.format("Healing triggers at random value %g", i),
          healingDecisionSupplier.shouldHeal(0.11),
          Matchers.equalTo(true));
    }
  }

  @Test
  public void healingAccepts50PercentOfKeysAtTheLimitOfLongDistance() {
    for (double i : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6)) {
      HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
          () -> 0.1,
          () -> true,
          () -> i);
      assertThat(String.format("Healing triggers at random value %g", i),
          healingDecisionSupplier.shouldHeal(0.1999),
          Matchers.equalTo(true));
    }
  }

  @Test
  public void healingAccepts10PercentOfKeysAtLongDistance() {
    for (double i : Arrays.asList(1.0, 0.91)) {
      HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
          () -> 0.1,
          () -> true,
          () -> i);
      assertThat(String.format("Healing triggers at random value %g", i),
          healingDecisionSupplier.shouldHeal(0.21),
          Matchers.equalTo(true));
    }
    for (double i : Arrays.asList(0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
      HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
          () -> 0.1,
          () -> true,
          () -> i);
      assertThat(String.format("Healing does not trigger at random value %g", i),
          healingDecisionSupplier.shouldHeal(0.21),
          Matchers.equalTo(false));
    }
  }


}
