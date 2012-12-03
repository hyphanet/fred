/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.keys;

import java.io.IOException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;

import net.i2p.util.NativeBigInteger;

import freenet.support.math.MersenneTwister;

import com.db4o.ObjectContainer;

import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.Util;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.Key.Compressed;
import freenet.support.api.Bucket;
import freenet.support.compress.InvalidCompressionCodecException;

/** A ClientSSK that has a private key and therefore can be inserted. */
public class InsertableClientSSK extends ClientSSK {

	public final DSAPrivateKey privKey;
	
	public InsertableClientSSK(String docName, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] cryptoKey, byte cryptoAlgorithm) throws MalformedURLException {
		super(docName, pubKeyHash, getExtraBytes(cryptoAlgorithm), pubKey, cryptoKey);
		if(pubKey == null) throw new NullPointerException();
		this.privKey = privKey;
	}
	
	public static InsertableClientSSK create(FreenetURI uri) throws MalformedURLException {
		if(uri.getKeyType().equalsIgnoreCase("KSK"))
			return ClientKSK.create(uri);

		if(uri.getRoutingKey() == null)
			throw new MalformedURLException("Insertable SSK URIs must have a private key!: "+uri);
		if(uri.getCryptoKey() == null)
			throw new MalformedURLException("Insertable SSK URIs must have a private key!: "+uri);
		
		byte keyType;

		byte[] extra = uri.getExtra();
		if(uri.getKeyType().equals("SSK")) {
			if(extra == null)
				throw new MalformedURLException("Inserting pre-1010 keys not supported");
			// Formatted exactly as ,extra on fetching
			if(extra.length < 5)
				throw new MalformedURLException("SSK private key ,extra too short");
			if(extra[1] != 1) {
				throw new MalformedURLException("SSK not a private key");
			}
			keyType = extra[2];
			if(keyType != Key.ALGO_AES_PCFB_256_SHA256)
				throw new MalformedURLException("Unrecognized crypto type in SSK private key");
		}
		else {
			throw new MalformedURLException("Not a valid SSK insert URI type: "+uri.getKeyType());
		}
		
		if((uri.getDocName() == null) || (uri.getDocName().length() == 0))
			throw new MalformedURLException("SSK URIs must have a document name (to avoid ambiguity)");
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(new NativeBigInteger(1, uri.getRoutingKey()), g);
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		byte[] pkHash = pubKey.asBytesHash();
		return new InsertableClientSSK(uri.getDocName(), pkHash, pubKey, privKey, uri.getCryptoKey(), keyType);
	}
	
	public ClientSSKBlock encode(Bucket sourceData, boolean asMetadata, boolean dontCompress, short alreadyCompressedCodec, long sourceLength, RandomSource r, String compressordescriptor, boolean pre1254) throws SSKEncodeException, IOException, InvalidCompressionCodecException {
		byte[] compressedData;
		short compressionAlgo;
		try {
			Compressed comp = Key.compress(sourceData, dontCompress, alreadyCompressedCodec, sourceLength, ClientSSKBlock.MAX_DECOMPRESSED_DATA_LENGTH, SSKBlock.DATA_LENGTH, true, compressordescriptor, pre1254);
			compressedData = comp.compressedData;
			compressionAlgo = comp.compressionAlgorithm;
		} catch (KeyEncodeException e) {
			throw new SSKEncodeException(e.getMessage(), e);
		}
		// Pad it
		MessageDigest md256 = SHA256.getMessageDigest();
		try {
			byte[] data;
			// First pad it
			if (compressedData.length != SSKBlock.DATA_LENGTH) {
				// Hash the data
				if (compressedData.length != 0)
					md256.update(compressedData);
				byte[] digest = md256.digest();
				MersenneTwister mt = new MersenneTwister(digest);
				data = Arrays.copyOf(compressedData, SSKBlock.DATA_LENGTH);
				if (compressedData.length > data.length) {
					throw new RuntimeException("compressedData.length = " + compressedData.length + " but data.length="
							+ data.length);
				}
				Util.randomBytes(mt, data, compressedData.length, SSKBlock.DATA_LENGTH - compressedData.length);
			} else {
				data = compressedData;
			}

			// Implicit hash of data
			byte[] origDataHash = md256.digest(data);

			Rijndael aes;
			try {
				aes = new Rijndael(256, 256);
			} catch (UnsupportedCipherException e) {
				throw new Error("256/256 Rijndael not supported!");
			}

			// Encrypt data. Data encryption key = H(plaintext data).

			aes.initialize(origDataHash);
			PCFBMode pcfb = PCFBMode.create(aes, origDataHash);

			pcfb.blockEncipher(data, 0, data.length);

			byte[] encryptedDataHash = md256.digest(data);

			// Create headers

			byte[] headers = new byte[SSKBlock.TOTAL_HEADERS_LENGTH];
			// First two bytes = hash ID
			int x = 0;
			headers[x++] = (byte) (KeyBlock.HASH_SHA256 >> 8);
			headers[x++] = (byte) (KeyBlock.HASH_SHA256);
			// Then crypto ID
			headers[x++] = (byte) (Key.ALGO_AES_PCFB_256_SHA256 >> 8);
			headers[x++] = Key.ALGO_AES_PCFB_256_SHA256;
			// Then E(H(docname))
			// Copy to headers
			System.arraycopy(ehDocname, 0, headers, x, ehDocname.length);
			x += ehDocname.length;
			// Now the encrypted headers
			byte[] encryptedHeaders = Arrays.copyOf(origDataHash, SSKBlock.ENCRYPTED_HEADERS_LENGTH);
			int y = origDataHash.length;
			short len = (short) compressedData.length;
			if (asMetadata)
				len |= 32768;
			encryptedHeaders[y++] = (byte) (len >> 8);
			encryptedHeaders[y++] = (byte) len;
			encryptedHeaders[y++] = (byte) (compressionAlgo >> 8);
			encryptedHeaders[y++] = (byte) compressionAlgo;
			if (encryptedHeaders.length != y)
				throw new IllegalStateException("Have more bytes to generate encoding SSK");
			aes.initialize(cryptoKey);
			pcfb.reset(ehDocname);
			pcfb.blockEncipher(encryptedHeaders, 0, encryptedHeaders.length);
			System.arraycopy(encryptedHeaders, 0, headers, x, encryptedHeaders.length);
			x += encryptedHeaders.length;
			// Generate implicit overall hash.
			md256.update(headers, 0, x);
			md256.update(encryptedDataHash);
			byte[] overallHash = md256.digest();
			// Now sign it
			DSASignature sig = DSA.sign(pubKey.getGroup(), privKey, new NativeBigInteger(1, overallHash), r);
			// Pack R and S into 32 bytes each, and copy to headers.

			// Then create and return the ClientSSKBlock.
			byte[] rBuf = truncate(sig.getR().toByteArray(), SSKBlock.SIG_R_LENGTH);
			byte[] sBuf = truncate(sig.getS().toByteArray(), SSKBlock.SIG_S_LENGTH);
			System.arraycopy(rBuf, 0, headers, x, rBuf.length);
			x += rBuf.length;
			System.arraycopy(sBuf, 0, headers, x, sBuf.length);
			x += sBuf.length;
			if (x != SSKBlock.TOTAL_HEADERS_LENGTH)
				throw new IllegalStateException("Too long");
			try {
				return new ClientSSKBlock(data, headers, this, true);
			} catch (SSKVerifyException e) {
				throw (AssertionError)new AssertionError("Impossible encoding error").initCause(e);
			}
		} finally {
			SHA256.returnMessageDigest(md256);
		}
	}

	private byte[] truncate(byte[] bs, int len) {
		if(bs.length == len)
			return bs;
		else if (bs.length < len) {
			byte[] buf = new byte[len];
			System.arraycopy(bs, 0, buf, len - bs.length, bs.length);
			return buf;
		} else { // if (bs.length > len) {
			for(int i=0;i<(bs.length-len);i++) {
				if(bs[i] != 0)
					throw new IllegalStateException("Cannot truncate");
			}
			return Arrays.copyOfRange(bs, bs.length-len, bs.length);
		}
	}

	public static InsertableClientSSK createRandom(RandomSource r, String docName) {
		byte[] ckey = new byte[CRYPTO_KEY_LENGTH];
		r.nextBytes(ckey);
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(g, r);
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		try {
			byte[] pkHash = SHA256.digest(pubKey.asBytes());
			return new InsertableClientSSK(docName, pkHash, pubKey, privKey, ckey, 
					Key.ALGO_AES_PCFB_256_SHA256);
		} catch (MalformedURLException e) {
			throw new Error(e);
		}
	}

	public FreenetURI getInsertURI() {
		return new FreenetURI("SSK", docName, privKey.getX().toByteArray(), cryptoKey, getInsertExtraBytes());
	}

	private byte[] getInsertExtraBytes() {
		byte[] extra = getExtraBytes();
		extra[1] = 1; // insert
		return extra;
	}

	public DSAGroup getCryptoGroup() {
		return Global.DSAgroupBigA;
	}
	
	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(privKey, 5);
		privKey.removeFrom(container);
		super.removeFrom(container);
	}
	
}
