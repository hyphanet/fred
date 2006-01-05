package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import freenet.support.Base64;
import freenet.support.Fields;

/**
 * @author amphibian
 * 
 * Node-level CHK. Does not have enough information to decode the payload.
 * But can verify that it is intact. Just has the routingKey.
 */
public class NodeCHK extends Key {

    public NodeCHK(byte[] routingKey2) {
    	super(routingKey2);
        if(routingKey2.length != KEY_LENGTH)
            throw new IllegalArgumentException("Wrong length: "+routingKey2.length+" should be "+KEY_LENGTH);
    }

    static final int KEY_LENGTH = 32;
    
    // 01 = CHK, 01 = first version of CHK
    public static final short TYPE = 0x0101;
    /** The size of the data */
	public static final int BLOCK_SIZE = 32768;

    public final void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        write(stream);
    }

    public String toString() {
        return super.toString() + "@"+Base64.encode(routingKey)+":"+Integer.toHexString(hash);
    }

    public final void write(DataOutput _index) throws IOException {
        _index.writeShort(TYPE);
        _index.write(routingKey);
    }
    
    public static Key read(DataInput raf) throws IOException {
        byte[] buf = new byte[KEY_LENGTH];
        raf.readFully(buf);
        return new NodeCHK(buf);
    }

    public boolean equals(Object key) {
        if(key instanceof NodeCHK) {
            NodeCHK chk = (NodeCHK) key;
            return java.util.Arrays.equals(chk.routingKey, routingKey);
        }
        return false;
    }

	public short getType() {
		return TYPE;
	}
    
    public byte[] getRoutingKey(){
    	return routingKey;
    }
}
