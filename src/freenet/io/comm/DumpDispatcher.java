package freenet.io.comm;

/**
 * Dispatcher that just dumps everything received to stderr.
 */
public class DumpDispatcher implements Dispatcher {

    public DumpDispatcher() {
    }

    public boolean handleMessage(Message m) {
        System.err.println("Received message: "+m);
        return true;
    }
}
