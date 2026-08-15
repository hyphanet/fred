package freenet.node.probe;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import java.util.Timer;
import java.util.TimerTask;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.node.SeedServerTestPeerNode;

public class ProbeTest {

  static class DirectlyRunningTimer extends Timer {
    public void schedule(TimerTask task, long delay) {
      task.run();
    }
  }

  /**
   * Tagging class for the error.
   */
  static class DoesNotRandomizeLocationException extends IllegalStateException {
  }
  @Test
  public void locationIsRandomized() {
    // with a minimal node with static location, fake random and empty peer list
    Node node = mock(Node.class, RETURNS_DEEP_STUBS);
    when(node.getLocation()).thenReturn(0.5);
    when(node.getConnectedPeers()).thenReturn(new PeerNode[]{});
    when(node.getRandom().nextDouble()).thenReturn(0.4);
    when(node.getConfig().get("node").getBoolean("probeLocation")).thenReturn(true);

    // and a listener that throws when receiving an invalid value
    Listener listener = mock(Listener.class, RETURNS_DEEP_STUBS);
    doThrow(DoesNotRandomizeLocationException.class).when(listener).onLocation(0.5f);

    // and a probe that runs requests instantly
    Probe probe = new Probe(node);
    probe.setTimer(new DirectlyRunningTimer());

    // check that the location is randomized (the listener does not throw)
    probe.start((byte)1, 12345L, Type.LOCATION, listener);

    // and that the listener does throw when the location is not randomized
    when(node.getRandom().nextDouble()).thenReturn(0.5);
    assertThrows(DoesNotRandomizeLocationException.class,
        () -> probe.start((byte)1, 12345L, Type.LOCATION, listener));
  }
}
