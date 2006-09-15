/*
  JavaSHA1.java / Freenet
  Copyright (C) 2003 Matthew Toseland
  Copyright (C) 2005-2006 The Free Network project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.crypt;
import java.security.*;
/*
  Implements an SHA-1 digest using javax.crypto
*/

class JavaSHA1 implements Digest {
    
    MessageDigest digest;
    
    public Object clone() throws CloneNotSupportedException {
	return new JavaSHA1((MessageDigest)(digest.clone()));
    }
    
    protected JavaSHA1(MessageDigest d) {
	digest = d;
    }
    
    public JavaSHA1() throws Exception {
	digest = MessageDigest.getInstance("SHA1");
    }
    
    public void extract(int[] digest, int offset) {
	throw new UnsupportedOperationException();
    }
    
    public void update(byte b) {
	digest.update(b);
    }
    
    public void update(byte[] data, int offset, int length) {
	digest.update(data, offset, length);
    }
    
    public void update(byte[] data) {
	digest.update(data);
    }
    
    public byte[] digest() {
	return digest.digest();
    }
    
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
    
    public int digestSize() {
	return 160;
    }
}
