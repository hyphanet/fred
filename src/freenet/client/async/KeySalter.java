package freenet.client.async;

import freenet.keys.Key;

/** This is a separate interface so we don't need to pass the scheduler where it's not needed. Also, it
 * should probably be a separate class ...
 * @author toad
 */
public interface KeySalter {

    /** Convert a Key to a byte[], using a global, random salt value. */
    byte[] saltKey(Key key);

}
