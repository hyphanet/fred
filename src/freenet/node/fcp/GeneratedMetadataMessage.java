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
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.BucketTools;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class GeneratedMetadataMessage extends BaseDataCarryingMessage {
    static final String NAME = "GeneratedMetadata";
    private final Bucket data;
    final String identifier;
    final boolean global;

    GeneratedMetadataMessage(String identifier, boolean global, Bucket data) {
        this.identifier = identifier;
        this.global = global;
        this.data = data;
    }

    @Override
    long dataLength() {
        return data.size();
    }

    @Override
    public void readFrom(InputStream is, BucketFactory bf, FCPServer server)
            throws IOException, MessageInvalidException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void writeData(OutputStream os) throws IOException {
        BucketTools.copyTo(data, os, data.size());
    }

    @Override
    public SimpleFieldSet getFieldSet() {
        SimpleFieldSet fs = new SimpleFieldSet(true);

        fs.putSingle("Identifier", identifier);
        fs.put("Global", global);
        fs.put("DataLength", data.size());

        return fs;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeFrom(ObjectContainer container) {

        // Bucket is the responsibility of the insert.
        container.delete(this);
    }
}
