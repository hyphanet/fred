/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.FetchException;

/**
 * Thrown when creating a KeyListener fails.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 *
 */
public class KeyListenerConstructionException extends Exception {
    final private static long serialVersionUID = 8246734637696483122L;

    KeyListenerConstructionException(FetchException e) {
        super(e);
    }

    public FetchException getFetchException() {
        return (FetchException) getCause();
    }
}
