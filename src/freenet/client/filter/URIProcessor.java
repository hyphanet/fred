/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.filter;

//~--- JDK imports ------------------------------------------------------------

import java.net.URISyntaxException;

/** This interface provides methods for URI transformations */
public interface URIProcessor {

    /** Processes an URI. If it is unsafe, then return null */
    public String processURI(String u, String overrideType, boolean noRelative, boolean inline) throws CommentException;

    /**
     * Makes an URI absolute
     *
     * @param uri
     *            - The uri to be absolutize
     * @return The absolute URI
     */
    public String makeURIAbsolute(String uri) throws URISyntaxException;
}
