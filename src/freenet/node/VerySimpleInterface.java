package freenet.node;

/**
 * Very simple interface. Reads commands from stdin and either
 * succeeds or fails.
 */
public class VerySimpleInterface implements Runnable {

    SimpleClientInterface node;
    
    public VerySimpleInterface(SimpleClientInterface node) {
        this.node = node;
    }

    public void run() {
        // Read get/put commands from stdin, and execute them
        // TODO Auto-generated method stub
    }

}
