package freenet.node.probe;

import freenet.node.Node;
import freenet.node.PeerNode;
import java.util.Timer;
import java.util.TimerTask;
import org.junit.Test;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProbeTest {

  @Test
  public void probeLocationIsRandomized() {
    when(node.getLocation()).thenReturn(0.4);
    when(node.getRandom().nextGaussian()).thenReturn(0.1);

    probe.start((byte) 1, 12345L, Type.LOCATION, listener);
    verify(listener).onLocation(0.4f + 0.1f * 0.01f / 2.0f);
  }

  @Test
  public void overflowOnNodeLocationRandomizationIsHandledCorrectly() {
    when(node.getLocation()).thenReturn(0.999);
    when(node.getRandom().nextGaussian()).thenReturn(1.0);

    probe.start((byte) 1, 12345L, Type.LOCATION, listener);
    verify(listener).onLocation((float) ((0.999 + 1.0 * 0.01 / 2.0) - 1.0));
  }

  @Test
  public void underflowOnNodeLocationRandomizationIsHandledCorrectly() {
    when(node.getLocation()).thenReturn(0.001);
    when(node.getRandom().nextGaussian()).thenReturn(-1.0);

    probe.start((byte) 1, 12345L, Type.LOCATION, listener);
    verify(listener).onLocation((float) ((0.001 - 1.0 * 0.01 / 2.0) + 1.0));
  }

  public ProbeTest() {
    node = mock(Node.class, RETURNS_DEEP_STUBS);
    when(node.getConnectedPeers()).thenReturn(new PeerNode[] {});
    when(node.getConfig().get("node").getBoolean("probeLocation")).thenReturn(true);
    when(node.getRandom().nextDouble()).thenReturn(0.5);
    listener = mock(Listener.class, RETURNS_DEEP_STUBS);
    probe = new Probe(node);
    probe.setTimer(new DirectlyRunningTimer());
  }

  private static class DirectlyRunningTimer extends Timer {

    public void schedule(TimerTask task, long delay) {
      task.run();
    }

  }

  private final Node node;
  private final Listener listener;
  private final Probe probe;

}
