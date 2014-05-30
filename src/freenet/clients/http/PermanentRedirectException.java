/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http;

//~--- JDK imports ------------------------------------------------------------

import java.net.URI;

public class PermanentRedirectException extends Exception {
    private static final long serialVersionUID = -166786248237623796L;
    URI newuri;

    public PermanentRedirectException() {
        super();
    }

    public PermanentRedirectException(URI newURI) {
        this.newuri = newURI;
    }
}
