/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.client;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.keys.FreenetURI;

import freenet.support.api.Bucket;

/**
 * Class to contain everything needed for an insert.
 */

//WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class InsertBlock {
    private Bucket data;
    private boolean isFreed;
    public FreenetURI desiredURI;
    public ClientMetadata clientMetadata;

    public InsertBlock(Bucket data, ClientMetadata metadata, FreenetURI desiredURI) {
        if (data == null) {
            throw new NullPointerException();
        }

        this.data = data;
        this.isFreed = false;

        if (metadata == null) {
            clientMetadata = new ClientMetadata();
        } else {
            clientMetadata = metadata;
        }

        this.desiredURI = desiredURI;
    }

    public Bucket getData() {
        return (isFreed ? null : data);
    }

    public void free(ObjectContainer container) {
        synchronized (this) {
            if (isFreed) {
                return;
            }

            isFreed = true;

            if (data == null) {
                return;
            }
        }

        data.free();

        if (container != null) {
            data.removeFrom(container);
            data = null;    // don't remove twice
        }

        if (container != null) {
            container.store(this);
        }
    }

    public void removeFrom(ObjectContainer container) {
        if (data != null) {
            container.activate(data, 1);
            data.removeFrom(container);
        }

        if (desiredURI != null) {
            container.activate(desiredURI, 5);
            desiredURI.removeFrom(container);
        }

        if (clientMetadata != null) {
            container.activate(clientMetadata, 5);
            clientMetadata.removeFrom(container);
        }

        container.delete(this);
    }

    public void objectOnActivate(ObjectContainer container) {

        // Cascading activation of dependancies
        container.activate(data, 1);    // will cascade
        container.activate(desiredURI, 5);
    }

    /**
     * Null out the data so it doesn't get removed in removeFrom().
     * Call this when the data becomes somebody else's problem. 
     */
    public void nullData() {
        data = null;
    }

    /**
     * Null out the URI so it doesn't get removed in removeFrom().
     * Call this when the URI becomes somebody else's problem. 
     */
    public void nullURI() {
        this.desiredURI = null;
    }

    public void nullMetadata() {
        this.clientMetadata = null;
    }
}
