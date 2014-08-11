package freenet.client.async;

/** Thrown when the file being loaded appears not to be a stored splitfile or other request. */
public class StorageFormatException extends Exception {

    public StorageFormatException(String message) {
        super(message);
    }
    
}