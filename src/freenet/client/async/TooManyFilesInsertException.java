package freenet.client.async;

/** Thrown when there are too many files in a single folder (directory) for an insert. It won't succeed
 * because the metadata will be too big, so we refuse to start it at all. */
public class TooManyFilesInsertException extends Exception {

}
