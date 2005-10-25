package freenet.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import freenet.crypt.PCFBMode;

/**
 * A proxy Bucket which adds:
 * - Encryption with the supplied cipher.
 * - Padding to the next PO2 size.
 */
public class PaddedEncryptedBucket implements Bucket {

	/**
	 * Create a padded encrypted proxy bucket.
	 * @param bucket The bucket which we are proxying to.
	 * @param pcfb The encryption mode with which to encipher/decipher the data.
	 */
	public PaddedEncryptedBucket(Bucket bucket, PCFBMode pcfb, int minSize) {
		// TODO Auto-generated constructor stub
	}

	public OutputStream getOutputStream() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public InputStream getInputStream() throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public String getName() {
		// TODO Auto-generated method stub
		return null;
	}

	public void resetWrite() throws IOException {
		// TODO Auto-generated method stub

	}

	public long size() {
		// TODO Auto-generated method stub
		return 0;
	}

	public byte[] toByteArray() {
		// TODO Auto-generated method stub
		return null;
	}

}
