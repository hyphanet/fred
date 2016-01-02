package freenet.support;

/** Waits for some other thread to signal to proceed. */
public class Waiter {
    
    private boolean completed;
    
    public Waiter() {
        completed = false;
    }

    public synchronized void complete() {
        completed = true;
        notifyAll();
    }

    public synchronized void waitForCompletion() throws InterruptedException {
        while(!completed) {
            wait();
        }
    }
    
}
