/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client.async;

//~--- non-JDK imports --------------------------------------------------------

import freenet.node.SendableRequest;

/**
 * These must be deleted once the request has been registered.
 * See DatastoreCheckerItem: this class only handles inserts.
 * @author toad
 */

//WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class RegisterMe {
    final SendableRequest nonGetRequest;
    final ClientRequestSchedulerCore core;
    final long addedTime;
    final short priority;

    /**
     * Only set if the key is on the queue.
     */
    final long bootID;
    private final int hashCode;
    public final BlockSet blocks;

    RegisterMe(SendableRequest nonGetRequest, short prio, ClientRequestSchedulerCore core, BlockSet blocks, long bootID) {
        this.bootID = bootID;
        this.core = core;
        this.nonGetRequest = nonGetRequest;
        priority = prio;
        addedTime = System.currentTimeMillis();
        this.blocks = blocks;

        int hash = core.hashCode();

        hash ^= nonGetRequest.hashCode();
        hash *= prio;
        hashCode = hash;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
