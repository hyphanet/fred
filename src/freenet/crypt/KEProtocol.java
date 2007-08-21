/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines the interface that must be implemented by key-exchange protocols
 * such as RSA and Diffie-Hellman
 */
public abstract class KEProtocol {
    protected RandomSource randomSource;
    protected EntropySource es;

    public KEProtocol(RandomSource rs) {
	randomSource=rs;
	es=new EntropySource();
    }

    public abstract void negotiateKey(InputStream in, OutputStream out,
				      byte[] key, int offset, int len) 
				      throws IOException;
}
