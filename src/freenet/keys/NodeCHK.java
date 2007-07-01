/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import freenet.support.Base64;

/**
 * @author amphibian
 * 
 * Node-level CHK. Does not have enough information to decode the payload.
 * But can verify that it is intact. Just has the routingKey.
 */
public class NodeCHK extends Key {

    /** 32 bytes for hash, 2 bytes for type */
    public static final short KEY_SIZE_ON_DISK = 34;
	
    public NodeCHK(byte[] routingKey2, byte cryptoAlgorithm) {
    	super(routingKey2);
        if(routingKey2.length != KEY_LENGTH)
            throw new IllegalArgumentException("Wrong length: "+routingKey2.length+" should be "+KEY_LENGTH);
        this.cryptoAlgorithm = cryptoAlgorithm;
    }

    static final int KEY_LENGTH = 32;
    
	/** Crypto algorithm */
	final byte cryptoAlgorithm;
    /** The size of the data */
	public static final int BLOCK_SIZE = 32768;

	public static final byte BASE_TYPE = 1;

    public final void writeToDataOutputStream(DataOutputStream stream) throws IOException {
        write(stream);
    }

    public String toString() {
        return super.toString() + '@' +Base64.encode(routingKey)+ ':' +Integer.toHexString(hash);
    }

    public final void write(DataOutput _index) throws IOException {
        _index.writeShort(getType());
        _index.write(routingKey);
    }
    
    public static Key readCHK(DataInput raf, byte algo) throws IOException {
        byte[] buf = new byte[KEY_LENGTH];
        raf.readFully(buf);
        return new NodeCHK(buf, algo);
    }

    public boolean equals(Object key) {
        if(key instanceof NodeCHK) {
            NodeCHK chk = (NodeCHK) key;
            return java.util.Arrays.equals(chk.routingKey, routingKey) && (cryptoAlgorithm == chk.cryptoAlgorithm);
        }
        return false;
    }
    
    public int hashCode(){
    	return super.hashCode();
    }
    
	public short getType() {
		return (short) (0x100 + (cryptoAlgorithm & 0xFF));
	}
    
    public byte[] getRoutingKey(){
    	return routingKey;
    }
}
