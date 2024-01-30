package freenet.client.async;

import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Arrays;

import org.hamcrest.Matchers;
import org.junit.Test;

public class HealingDecisionSupplierTest {

  @Test
  public void healingAlwaysTriggersForDarknet() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  assertHeals(
		  getHealingDecisionSupplier(0.1, false, randomValue),
		  0.5,
		  randomValue);
	}
  }

  @Test
  public void healingAlwaysAcceptsAKeyAtTheNodeLocation() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  assertHeals(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.1,
		  randomValue);
	}
  }

  @Test
  public void healingAccepts70PercentOfKeysInShortDistance() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4)) {
	  assertHeals(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.11,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.3, 0.2, 0.1, 0.00000001)) {
	  assertDoesNotHeal(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.11,
		  randomValue);
	}
  }

  @Test
  public void healingAccepts50PercentOfKeysAtTheLimitOfLongDistance() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6)) {
	  assertHeals(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.1999,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  assertDoesNotHeal(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.1999,
		  randomValue);
	}
  }

  @Test
  public void healingAccepts50PercentOfKeysAtTheLimitOfLongDistanceAtADifferentNodeLocationToo() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.6,
		  true,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.6999,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.5001,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.6,
		  true,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.1999,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.5001,
		  randomValue);
	}
  }

  @Test
  public void healingAccepts50PercentOfKeysAtTheLimitOfLongDistanceAroundZero() {
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.0001,
		  true,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.1,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.9002,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(1.0, 0.9, 0.8, 0.7, 0.6)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.9999,
		  true,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.0998,
		  randomValue);
	  assertHeals(
		  healingDecisionSupplier,
		  0.9000,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.0001,
		  true,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.1,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.9002,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  HealingDecisionSupplier healingDecisionSupplier = getHealingDecisionSupplier(
		  0.9999,
		  true,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.0998,
		  randomValue);
	  assertDoesNotHeal(
		  healingDecisionSupplier,
		  0.9000,
		  randomValue);
	}
  }

  @Test
  public void healingAccepts10PercentOfKeysAtLongDistance() {
	for (double randomValue : Arrays.asList(1.0, 0.91)) {
	  assertHeals(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.21,
		  randomValue);
	}
	for (double randomValue : Arrays.asList(0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0.00000001)) {
	  assertDoesNotHeal(
		  getHealingDecisionSupplier(0.1, true, randomValue),
		  0.21,
		  randomValue);
	}
  }

  private static HealingDecisionSupplier getHealingDecisionSupplier(double nodeLocation, boolean isOpennet, double randomValue) {
	HealingDecisionSupplier healingDecisionSupplier = new HealingDecisionSupplier(
		() -> nodeLocation,
		() -> isOpennet,
		() -> randomValue);
	return healingDecisionSupplier;
  }

  private static void assertHeals(
	  HealingDecisionSupplier healingDecisionSupplier,
	  double keyLocation, double randomValue) {
	assertThat(
		String.format("Healing triggers at random value %g", randomValue),
		healingDecisionSupplier.shouldHeal(keyLocation),
		Matchers.equalTo(true));
  }

  private static void assertDoesNotHeal(
	  HealingDecisionSupplier healingDecisionSupplier,
	  double keyLocation, double randomValue) {
	assertThat(
		String.format("Healing does not trigger at random value %g", randomValue),
		healingDecisionSupplier.shouldHeal(keyLocation),
		Matchers.equalTo(false));
  }

}
