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

public class ExpectedDataLength extends FCPMessage {
    final String identifier;
    final boolean global;
    final long dataLength;

    ExpectedDataLength(String identifier, boolean global, long dataLength) {
        this.identifier = identifier;
        this.global = global;
        this.dataLength = dataLength;
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(false);

        fs.putOverwrite("Identifier", identifier);
        fs.put("Global", global);
        fs.put("DataLength", dataLength);

        return fs;
    }

    @Override
    public String getName() {
        return "ExpectedDataLength";
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
