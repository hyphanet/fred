package freenet.keys;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import freenet.io.WritableToDataOutputStream;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;

/**
 * @author amphibian
 * 
 * Node-level CHK. Does not have enough information to decode the payload.
 * But can verify that it is intact. Just has the routingKey.
 */
public class NodeCHK extends Key implements WritableToDataOutputStream {

    final int hash;
    
    public NodeCHK(byte[] routingKey2) {
        routingKey = routingKey2;
        if(routingKey2.length != KEY_LENGTH)
            throw new IllegalArgumentException("Wrong length: "+routingKey2.length+" should be "+KEY_LENGTH);
        hash = Fields.hashCode(routingKey);
    }

    static final int KEY_LENGTH = 20;
    
    byte[] routingKey;
    public static final short TYPE = 0x0302;

    public void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        stream.write(routingKey);
    }

    public String toString() {
        return super.toString() + "@"+Base64.encode(routingKey)+":"+Integer.toHexString(hash);
    }

    public void write(RandomAccessFile _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(routingKey);
    }
    
    public static Key read(RandomAccessFile raf) throws IOException {
        byte[] buf = new byte[KEY_LENGTH];
        raf.readFully(buf);
        return new NodeCHK(buf);
    }

    public int hashCode() {
        System.out.println("Hash code is "+hash);
        new Exception().printStackTrace();
        return hash;
    }

    public boolean equals(Object key) {
        Logger.debug(this, "Doing equals("+key+")", new Exception());
        if(key instanceof NodeCHK) {
            NodeCHK chk = (NodeCHK) key;
            return java.util.Arrays.equals(chk.routingKey, routingKey);
        }
        return false;
    }
}
