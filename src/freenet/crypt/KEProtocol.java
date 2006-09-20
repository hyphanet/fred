package freenet.crypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Defines the interface that must be implemented by key-exchange protocols
 * such as RSA and Diffie-Helman
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
