package freenet.node;

import freenet.keys.CHKBlock;
import freenet.keys.Key;

/**
 * Simple blocking interface to the node for clients.
 */
public interface SimpleClientInterface {

    /** Get a key, and return it. Or return null in case of error. */
    CHKBlock simpleGet(Key k);
    
    /** Put the key, or return false */
    boolean simplePut(CHKBlock chk);
}
