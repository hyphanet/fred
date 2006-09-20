package freenet.keys;

/**
 * Interface for fetched blocks. Can be decoded with a key.
 */
public interface KeyBlock {

    final static int HASH_SHA256 = 1;
	
    public Key getKey();
    public byte[] getRawHeaders();
    public byte[] getRawData();

}
