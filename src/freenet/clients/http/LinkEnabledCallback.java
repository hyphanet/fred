/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http;

public interface LinkEnabledCallback {

    /**
     * Whether to show the link?
     * @param ctx The request which is asking. Can be null. 
     */
    boolean isEnabled(ToadletContext ctx);
}
