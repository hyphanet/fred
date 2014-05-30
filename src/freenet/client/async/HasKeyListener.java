/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;

/**
 * Interface to show that we can create a KeyListener callback.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface HasKeyListener {

    /**
     * Create a KeyListener, a transient object used to determine which keys we
     * want, and to handle any blocks found.
     * @return Null if the HasKeyListener is finished/cancelled/etc.
     * @throws IOException
     */
    KeyListener makeKeyListener(ObjectContainer container, ClientContext context, boolean onStartup)
            throws KeyListenerConstructionException;

    /**
     * Is it cancelled?
     */
    boolean isCancelled(ObjectContainer container);

    /**
     * Notify that makeKeyListener() failed.
     */
    void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context);
}
