package freenet.l10n;

/**
 * Thrown when an invalid l10n replacement string is encountered. Used internally in BaseL10n.
 */
class L10nParseException extends Exception {
    private static final long serialVersionUID = 1L;
    
    public L10nParseException(String message) {
        super(message);
    }
    
    public L10nParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
