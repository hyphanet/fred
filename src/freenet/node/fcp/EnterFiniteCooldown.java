/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import com.db4o.ObjectContainer;

import freenet.node.Node;

import freenet.support.SimpleFieldSet;

/**
 * Status message sent when the whole of a request is waiting for a cooldown.
 * Not when it's all running - that would be a different event.
 * @author toad
 */
public class EnterFiniteCooldown extends FCPMessage {
    final String identifier;
    final boolean global;
    final long wakeupTime;

    EnterFiniteCooldown(String identifier, boolean global, long wakeupTime) {
        this.identifier = identifier;
        this.global = global;
        this.wakeupTime = wakeupTime;
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(false);

        fs.putOverwrite("Identifier", identifier);
        fs.put("Global", global);
        fs.put("WakeupTime", wakeupTime);

        return fs;
    }

    @Override
    public String getName() {
        return "EnterFiniteCooldown";
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {

        // Not supported
    }

    @Override
    public void removeFrom(ObjectContainer container) {
        container.delete(this);
    }
}
