/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

class JavaSHA1 implements Digest, Cloneable {
    
    MessageDigest digest;
    
    @Override
	public Object clone() throws CloneNotSupportedException {
	return new JavaSHA1((MessageDigest)(digest.clone()));
    }
    
    protected JavaSHA1(MessageDigest d) {
	digest = d;
    }
    
    public JavaSHA1() throws NoSuchAlgorithmException {
	digest = MessageDigest.getInstance("SHA1");
    }
    
    @Override
    public void extract(int[] digest, int offset) {
	throw new UnsupportedOperationException();
    }
    
    @Override
    public void update(byte b) {
	digest.update(b);
    }
    
    @Override
    public void update(byte[] data, int offset, int length) {
	digest.update(data, offset, length);
    }
    
    @Override
    public void update(byte[] data) {
	digest.update(data);
    }
    
    @Override
    public byte[] digest() {
	return digest.digest();
    }
    
    @Override
    public void digest(boolean reset, byte[] buffer, int offset) {
	if(reset != true) throw new UnsupportedOperationException();
	try {
	    digest.digest(buffer, offset, digest.getDigestLength());
	} catch (DigestException e) {
	    throw new IllegalStateException(e.toString());
	}
    }
    
//     protected final int blockIndex() {
// 	return (int)((count / 8) & 63);
//     }
    
//     public void finish() {
// 	byte bits[] = new byte[8];
//         int i, j;
//         for (i = 0; i < 8; i++) {
//             bits[i] = (byte)((count >>> (((7 - i) << 3))) & 0xff);
//         }
	
//         update((byte) 128);
//         while (blockIndex() != 56)
//             update((byte) 0);
//         // This should cause a transform to happen.
//         for(i=0; i<8; ++i) update(bits[i]);
//     }
    
    @Override
    public int digestSize() {
	return 160;
    }
}
