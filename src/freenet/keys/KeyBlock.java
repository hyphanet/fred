package freenet.keys;

/**
 * Interface for fetched blocks. Can be decoded with a key.
 */
public interface KeyBlock {

	/** Decode with the key */
	byte[] decode(ClientKey key);

}
