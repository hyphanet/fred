package freenet.client.async;

import java.util.function.Supplier;

import freenet.node.Location;
import freenet.node.NodeStarter;

/**
 * Specialize Healing to the fraction of the keyspace in which we would receive the inserts
 * if we were one of 5 long distance nodes of an actual inserter.
 *
 * If an opennet node is connected to an attacker, healing traffic could be mistaken for an insert.
 * Since opennet cannot be fully secured, this should be avoided.
 * As a solution, we specialize healing inserts to the inserts we would send if we were one of 5
 * long distance connections for a node in another part of the keyspace.
 *
 * As a welcome side effect, specialized healing inserts should take one hop less to reach the
 * correct node from which loop detection will stop the insert long before HTL reaches zero.
 */
public class HealingDecisionSupplier {
	private final Supplier<Double> currentNodeLocation;
	private final Supplier<Boolean> isOpennetEnabled;
	private final Supplier<Double> randomNumberSupplier;

	public HealingDecisionSupplier(Supplier<Double> currentNodeLocation, Supplier<Boolean> isOpennetEnabled) {

		this.currentNodeLocation = currentNodeLocation;
		this.isOpennetEnabled = isOpennetEnabled;
		randomNumberSupplier = NodeStarter.getGlobalSecureRandom()::nextDouble;
	}

	HealingDecisionSupplier(Supplier<Double> currentNodeLocation, Supplier<Boolean> isOpennetEnabled, Supplier<Double> randomNumberSupplier) {

		this.currentNodeLocation = currentNodeLocation;
		this.isOpennetEnabled = isOpennetEnabled;
		this.randomNumberSupplier = randomNumberSupplier;
	}

	public boolean shouldHeal(double keyLocation) {
		if (!isOpennetEnabled.get()) {
			// darknet is safer against sybil attack, so we can heal fully
			return true;
		}
		double randomBetweenZeroAndOne = randomNumberSupplier.get();
		return shouldHealBlock(currentNodeLocation.get(), keyLocation, randomBetweenZeroAndOne);
	}

	/**
	 * Specialize healing: we want healing traffic to look like regular forwarding.
	 *
	 * Far away blocks would be unlikely to reach our node as request,
	 * so we reduce healing there: only accept 10% of those.
	 *
	 * When a key is close to our location, we use a continuous function depending on the distance
	 * to choose a probability. The closer to our node, the higher the probability of healing.
	 * As a side effect this reduces the hops healing inserts take, reducing the overall load
	 * on the network.
	 *
	 * The continuous function is gauged to accept 50% of the keys close to our node: a peak at our
	 * own location for which the area below the curve between 0 and 0.1 sums up to 0.5.
	 *
	 * Close keys are those in our 20% of the keyspace: the ones that would reach us if we were one of
	 * 5 long distance peers of a peer node. These are the keys for which we are most likely to be
	 * the best next hop when seen from the originator.
	 */
	private static boolean shouldHealBlock(
										   double nodeLocation,
										   double keyLocation,
										   double randomBetweenZeroAndOne) {
		double distanceToNodeLocation = Location.distance(nodeLocation, keyLocation);
		// If the key is inside "our" 20% of the keyspace, heal it with 50% probability.
		if (distanceToNodeLocation < 0.1) {
			// accept 50%, specialized to our own location (0.5 ** 4 ~ 0.0625). Accept 70% which are going
			// to our short distance peers (0.32 ** 4 ~ 0.01), 78% of those which could be reached via a
			// direct short distance FOAF (distance 0.02).
			double randomToPower4 = Math.pow(randomBetweenZeroAndOne, 4);
			return distanceToNodeLocation < randomToPower4;
		} else {
			// if the key is a long distance key for us, heal it with 10% probability: it is unlikely that
			// this would have reached us. Setting this to 0 could amplify a keyspace takeover attack.
			return randomBetweenZeroAndOne > 0.9;
		}
	}

}
