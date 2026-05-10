package freenet.node;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import junit.framework.TestCase;

@RunWith(Parameterized.class)
public class DNSRequesterTest extends TestCase {

	private final boolean peerHasHostname;
	private final boolean noConnectedPeers;
	private final int expectedTime;

	private Node node;
	private DNSRequester dnsRequester;

	@Parameters(name = "{index}: hostname: {0}, noConnected: {1} -> {2}")
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][] {
				{false, true, 2}, // shortest: has no hostname and has no connections
				{false, false, 11}, // has no hostname and has connections
				{true, true, 200}, // has hostname and has no connections
				{true, false, 1100}, // longest: has hostname and has connections
		});
	}

	public DNSRequesterTest(boolean peerHasHostname, boolean noConnectedPeers, int expectedTime) {
		this.peerHasHostname = peerHasHostname;
		this.noConnectedPeers = noConnectedPeers;
		this.expectedTime = expectedTime;
	}

	@Before
	public void setup(){
		this.node = mock(Node.class, RETURNS_DEEP_STUBS);
		when(node.getFastWeakRandom().nextInt(anyInt())).thenReturn(1);
		this.dnsRequester = new DNSRequester(node);
	}
	@Test
	public void shouldMatchExpectedTime() {
		assertThat(dnsRequester.getNextWaitTime(peerHasHostname, noConnectedPeers), Matchers.equalTo(expectedTime));
	}

}
