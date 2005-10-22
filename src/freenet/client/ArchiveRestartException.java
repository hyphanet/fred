package freenet.client;

/**
 * Thrown when we need to restart a fetch process because of a problem
 * with an archive. This is usually because an archive has changed
 * since we last checked.
 */
public class ArchiveRestartException extends Exception {

}
