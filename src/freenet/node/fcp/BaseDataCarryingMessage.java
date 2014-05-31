/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.node.fcp;

//~--- non-JDK imports --------------------------------------------------------

import freenet.support.api.BucketFactory;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class BaseDataCarryingMessage extends FCPMessage {
    abstract long dataLength();

    public abstract void readFrom(InputStream is, BucketFactory bf, FCPServer server)
            throws IOException, MessageInvalidException;

    @Override
    public void send(OutputStream os) throws IOException {
        super.send(os);
        writeData(os);
    }

    protected abstract void writeData(OutputStream os) throws IOException;
}
