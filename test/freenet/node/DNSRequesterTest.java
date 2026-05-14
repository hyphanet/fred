package freenet.node;

import freenet.io.comm.Peer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DNSRequesterTest {

	@Test
	public void dnsRequesterWillNotUpdateAnythingIfNoPeersArePresent() {
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[0], dnsRequester -> {
			assertThat(dnsRequester.updatedPeerNodes, empty());
		});
	}

	@Test
	public void dnsRequesterDoesNotUpdateConnectedPeers() {
		when(peerNode.isConnected()).thenReturn(true);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.updatedPeerNodes, empty());
		});
	}

	@Test
	public void dnsRequesterUpdateSinglePeerIfOnePeerIsPresent() {
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.updatedPeerNodes, contains(peerNode));
		});
	}

	@Test
	public void dnsRequesterWaitsVeryShortTimeIfPeerHasNoHostnameAndNoNodeIsConnected() {
		when(node.noConnectedPeers()).thenReturn(true);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithoutHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 1);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.waitTimes, contains(2L));
		});
	}

	@Test
	public void dnsRequesterUsesSmallVarianceOnRandomNumberGeneratorIfPeerHasNoHostnameAndNoNodeIsConnected() {
		when(node.noConnectedPeers()).thenReturn(true);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithoutHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 1);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor.forClass(Integer.class);
			verify(node.getFastWeakRandom(), times(2)).nextInt(argumentCaptor.capture());
			assertThat(argumentCaptor.getAllValues().get(1), equalTo(4));
		});
	}

	@Test
	public void dnsRequesterWaitsLongerTimeIfPeerHasHostnameAndNoNodeIsConnected() {
		when(node.noConnectedPeers()).thenReturn(true);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 1);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.waitTimes, contains(200L));
		});
	}

	@Test
	public void dnsRequesterUsesSmallVarianceOnRandomNumberGeneratorIfPeerHasHostnameAndNoNodeIsConnected() {
		when(node.noConnectedPeers()).thenReturn(true);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 1);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor.forClass(Integer.class);
			verify(node.getFastWeakRandom(), times(2)).nextInt(argumentCaptor.capture());
			assertThat(argumentCaptor.getAllValues().get(1), equalTo(4));
		});
	}

	@Test
	public void dnsRequesterWaitsLongerTimeIfPeerHasNoHostnameAndNodesAreConnected() {
		when(node.noConnectedPeers()).thenReturn(false);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithoutHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 500);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.waitTimes, contains(510L));
		});
	}

	@Test
	public void dnsRequesterUsesLargeVarianceOnRandomNumberGeneratorIfPeerHasNoHostnameAndNodesAreConnected() {
		when(node.noConnectedPeers()).thenReturn(false);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithoutHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 500);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor.forClass(Integer.class);
			verify(node.getFastWeakRandom(), times(2)).nextInt(argumentCaptor.capture());
			assertThat(argumentCaptor.getAllValues().get(1), equalTo(600));
		});
	}

	@Test
	public void dnsRequesterWaitsLongestTimeIfPeerHasHostnameAndNodesAreConnected() {
		when(node.noConnectedPeers()).thenReturn(false);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 500);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.waitTimes, contains(51000L));
		});
	}

	@Test
	public void dnsRequesterUsesLargeVarianceOnRandomNumberGeneratorIfPeerHasHostnameAndNodesAreConnected() {
		when(node.noConnectedPeers()).thenReturn(false);
		when(peerNode.getNominalPeer()).thenReturn(asList(peerWithHostname));
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 500);
		runDNSRequester(quitAfterFirstRun(), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor.forClass(Integer.class);
			verify(node.getFastWeakRandom(), times(2)).nextInt(argumentCaptor.capture());
			assertThat(argumentCaptor.getAllValues().get(1), equalTo(600));
		});
	}

	@Test
	public void dnsRequesterUpdatesRandomlySelectedPeerNode() {
		PeerNode[] peerNodes = generateRandomNodes(10);
		for (int peerIndex = 0; peerIndex < peerNodes.length; peerIndex++) {
			PeerNode peerNode = peerNodes[peerIndex];
			when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(peerIndex, 0);
			runDNSRequester(quitAfterFirstRun(), () -> peerNodes, dnsRequester -> {
				assertThat(dnsRequester.updatedPeerNodes, contains(peerNode));
			});
		}
	}

	@Test
	public void dnsRequesterWaitsRandomlyAfterEachPeerNode() {
		when(node.noConnectedPeers()).thenReturn(true);
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(0, 471595564, 0, 1794965883, 0, 2141731345, 0, 1549634095, 0, 274830936);
		runDNSRequester(quitAfterNRuns(5), () -> new PeerNode[] { peerNode }, dnsRequester -> {
			assertThat(dnsRequester.waitTimes, contains(471595564L + 1, 1794965883L + 1, 2141731345L + 1, 1549634095L + 1, 274830936L + 1));
		});
	}

	@Test
	public void dnsRequesterDoesNotReRequestUpdateForNodesWhileTheNumberOfRemainingNodesIsLargeEnough() {
		PeerNode[] peerNodes = generateRandomNodes(100);
		// the number of known nodes (i.e. nodes not to check again) must not be larger than 81% of the number of remaining nodes.
		// it takes 46 iterations for the number of remaining nodes to get small enough that the known nodes set gets culled.
		// 46 > (0.81 * 55), so two elements get removed from the known node set; however, this only gets relevant on the next iteration (see next test).
		runDNSRequester(quitAfterNRuns(46), () -> peerNodes, dnsRequester -> {
			assertThat(new HashSet<>(dnsRequester.updatedPeerNodes), hasSize(46));
		});
	}

	@Test
	public void dnsRequesterReRequestUpdateForNodesWhenMoreThan81PercentOfRemainingNodesHaveBeenChecked() {
		PeerNode[] peerNodes = generateRandomNodes(100);
		// the number of known nodes (i.e. nodes not to check again) must not be larger than 81% of the number of remaining nodes.
		// it takes 46 iterations for the number of remaining nodes to get small enough that the known nodes set gets culled.
		// 46 > (0.81 * 55), so two elements get removed from the known node set; at the next iteration, the oldest node has been
		// removed from the known nodes set, so the oldest node (i.e., the first in the peerNodes array) will be retried.
		runDNSRequester(quitAfterNRuns(47), () -> peerNodes, dnsRequester -> {
			assertThat(new HashSet<>(dnsRequester.updatedPeerNodes), hasSize(46));
		});
	}

	@Test(timeout = 5000L)
	public void dnsRequesterRequestsAllNodesWhenRunningLongEnough() {
		PeerNode[] peerNodes = generateRandomNodes(100);
		when(node.getFastWeakRandom().nextInt(anyInt())).thenAnswer(invocation -> (int) (Math.random() * (int) invocation.getArguments()[0]));
		// in experiments, it took between 300 and 400 iterations to reliably pick every node once, so runtime here should be negligible.
		runDNSRequester(quitWhen(dnsRequester -> new HashSet<>(dnsRequester.updatedPeerNodes).size() != 100), () -> peerNodes, dnsRequester -> {
		});
	}

	private void runDNSRequester(Function<TestDNSRequester, Boolean> shouldContinue, Supplier<PeerNode[]> peerNodes, Consumer<TestDNSRequester> dnsRequesterConsumer) {
		TestDNSRequester dnsRequester = new TestDNSRequester(node, shouldContinue, peerNodes);
		dnsRequester.run();
		dnsRequesterConsumer.accept(dnsRequester);
	}

	private static PeerNode[] generateRandomNodes(int numberOfNodes) {
		PeerNode[] peerNodes = new PeerNode[numberOfNodes];
		Arrays.setAll(peerNodes, index -> when(mock(PeerNode.class, "PeerNode " + index).getLocation()).thenReturn((index + 1) / (double) (numberOfNodes + 1)).getMock());
		return peerNodes;
	}

	private Function<TestDNSRequester, Boolean> quitAfterFirstRun() {
		return quitAfterNRuns(1);
	}

	private Function<TestDNSRequester, Boolean> quitAfterNRuns(int numberOfRuns) {
		AtomicInteger runsLeft = new AtomicInteger(numberOfRuns);
		return dnsRequester -> runsLeft.getAndDecrement() > 0;
	}

	private Function<TestDNSRequester, Boolean> quitWhen(Predicate<TestDNSRequester> predicate) {
		return predicate::test;
	}

	private final Node node = mock(Node.class, RETURNS_DEEP_STUBS);
	private final PeerNode peerNode = mock(PeerNode.class);
	private final Peer peerWithHostname = mock(Peer.class, RETURNS_DEEP_STUBS);
	private final Peer peerWithoutHostname = mock(Peer.class, RETURNS_DEEP_STUBS);

	{
		when(peerWithHostname.getFreenetAddress().hasHostname()).thenReturn(true);
	}

	private static class TestDNSRequester extends DNSRequester {

		public final List<PeerNode> updatedPeerNodes = new ArrayList<>();
		public final List<Long> waitTimes = new ArrayList<>();

		public TestDNSRequester(Node node, Function<TestDNSRequester, Boolean> shouldContinue, Supplier<PeerNode[]> peerNodes) {
			super(node);
			this.shouldContinue = shouldContinue;
			this.peerNodes = peerNodes;
		}

		@Override
		protected boolean shouldContinue() {
			return shouldContinue.apply(this);
		}

		@Override
		protected PeerNode[] getPeers() {
			return peerNodes.get();
		}

		@Override
		protected void updatePeer(PeerNode peerNode) {
			updatedPeerNodes.add(peerNode);
		}

		@Override
		protected void sleepUntilNextRun(long waitTime) {
			waitTimes.add(waitTime);
		}

		private final Function<TestDNSRequester, Boolean> shouldContinue;
		private final Supplier<PeerNode[]> peerNodes;

	}

}
