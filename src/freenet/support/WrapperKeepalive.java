package freenet.support;

import org.tanukisoftware.wrapper.WrapperManager;

import java.io.IOException;

import static java.util.concurrent.TimeUnit.MINUTES;

public class WrapperKeepalive extends Thread implements AutoCloseable {
  private volatile boolean shutdown = false;
  private static final int INTERVAL = (int) MINUTES.toMillis(2);

  @Override
  public void run() {
    while (!shutdown) {
      try {
        WrapperManager.signalStarting(INTERVAL);
        Thread.sleep(INTERVAL);
      } catch (InterruptedException e) {}
    }
  }

  @Override
  public void close() throws IOException {
    shutdown = true;
  }
}
