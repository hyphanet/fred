package freenet.support.io;

/** A Bucket or RandomAccessThing that will be freed only after client.dat* is written. */
public interface DelayedFree {
    
    boolean toFree();

    void realFree();

}