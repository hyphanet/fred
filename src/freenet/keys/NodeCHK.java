package freenet.keys;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.io.WritableToDataOutputStream;
import freenet.support.HexUtil;

/**
 * @author amphibian
 * 
 * Node-level CHK. Does not have enough information to decode the payload.
 * But can verify that it is intact. Just has the routingKey.
 */
public class NodeCHK implements WritableToDataOutputStream {

    public NodeCHK(byte[] routingKey2) {
        // FIXME: check size?
        routingKey = routingKey2;
    }

    byte[] routingKey;

    public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        stream.write(routingKey);
    }

    public String toString() {
        return super.toString() + "@"+HexUtil.bytesToHex(routingKey);
    }
}
