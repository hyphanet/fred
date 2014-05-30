/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http;

/**
 * Indicates that a request cannot be processed by the toadlet, due to missing or invalid data in the request.
 */
public class BadRequestException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String invalidRequestPart;

    public BadRequestException(String invalidRequestPart) {
        this.invalidRequestPart = invalidRequestPart;
    }

    public BadRequestException(String invalidRequestPart, String message) {
        super(message);
        this.invalidRequestPart = invalidRequestPart;
    }

    public BadRequestException(String invalidRequestPart, Throwable cause) {
        super(cause);
        this.invalidRequestPart = invalidRequestPart;
    }

    public BadRequestException(String invalidRequestPart, String message, Throwable cause) {
        super(message, cause);
        this.invalidRequestPart = invalidRequestPart;
    }

    public String getInvalidRequestPart() {
        return invalidRequestPart;
    }
}
