package freenet.keys;

import java.io.IOException;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.i2p.util.NativeBigInteger;

import org.spaceroots.mantissa.random.MersenneTwister;

import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.PCFBMode;
import freenet.crypt.RandomSource;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.Key.Compressed;
import freenet.support.Bucket;

public class InsertableClientSSK extends ClientSSK {

	public final DSAPrivateKey privKey;
	
	public InsertableClientSSK(String docName, byte[] pubKeyHash, DSAPublicKey pubKey, DSAPrivateKey privKey, byte[] cryptoKey) {
		super(docName, pubKeyHash, pubKey, cryptoKey);
		if(pubKey == null) throw new NullPointerException();
		this.privKey = privKey;
	}
	
	public static InsertableClientSSK create(FreenetURI uri) throws MalformedURLException {
		if(!uri.getKeyType().equalsIgnoreCase("SSK"))
			throw new MalformedURLException();
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(new NativeBigInteger(1, uri.getKeyVal()));
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		md.update(pubKey.asBytes());
		return new InsertableClientSSK(uri.getDocName(), md.digest(), pubKey, privKey, uri.getCryptoKey());
	}
	
	public ClientSSKBlock encode(Bucket sourceData, boolean asMetadata, boolean dontCompress, short alreadyCompressedCodec, long sourceLength, RandomSource r) throws SSKEncodeException, IOException {
		byte[] compressedData;
		short compressionAlgo;
		try {
			Compressed comp = Key.compress(sourceData, dontCompress, alreadyCompressedCodec, sourceLength, ClientSSKBlock.MAX_DECOMPRESSED_DATA_LENGTH, ClientSSKBlock.DATA_LENGTH);
			compressedData = comp.compressedData;
			compressionAlgo = comp.compressionAlgorithm;
		} catch (KeyEncodeException e) {
			throw new SSKEncodeException(e.getMessage(), e);
		}
		// Pad it
        MessageDigest md256;
        try {
            md256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e1) {
            // FIXME: log this properly?
            throw new Error(e1);
        }
        byte[] data;
        // First pad it
        if(compressedData.length != ClientSSKBlock.DATA_LENGTH) {
            // Hash the data
            if(compressedData.length != 0)
            	md256.update(compressedData);
            byte[] digest = md256.digest();
            MersenneTwister mt = new MersenneTwister(digest);
            data = new byte[ClientSSKBlock.DATA_LENGTH];
            System.arraycopy(compressedData, 0, data, 0, compressedData.length);
            byte[] randomBytes = new byte[ClientSSKBlock.DATA_LENGTH-compressedData.length];
            mt.nextBytes(randomBytes);
            System.arraycopy(randomBytes, 0, data, compressedData.length, ClientSSKBlock.DATA_LENGTH-compressedData.length);
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
		PCFBMode pcfb = new PCFBMode(aes);
		
		pcfb.blockEncipher(data, 0, data.length);
		
		byte[] encryptedDataHash = md256.digest(data);
		
        // Create headers
        
        byte[] headers = new byte[SSKBlock.TOTAL_HEADERS_LENGTH];
        // First two bytes = hash ID
        int x = 0;
        headers[x++] = (byte) (ClientSSKBlock.HASH_SHA256 >> 8);
        headers[x++] = (byte) (ClientSSKBlock.HASH_SHA256);
        // Then crypto ID
        headers[x++] = (byte) (Key.ALGO_AES_PCFB_256 >> 8);
        headers[x++] = (byte) (Key.ALGO_AES_PCFB_256);
        // Then E(H(docname))
		// Copy to headers
		System.arraycopy(ehDocname, 0, headers, x, ehDocname.length);
		x += ehDocname.length;
		// Now the encrypted headers
		byte[] encryptedHeaders = new byte[SSKBlock.ENCRYPTED_HEADERS_LENGTH];
		System.arraycopy(origDataHash, 0, encryptedHeaders, 0, origDataHash.length);
		int y = origDataHash.length;
		short len = (short) compressedData.length;
		if(asMetadata) len |= 32768;
		encryptedHeaders[y++] = (byte)(len >> 8);
		encryptedHeaders[y++] = (byte)len;
		encryptedHeaders[y++] = (byte)(compressionAlgo >> 8);
		encryptedHeaders[y++] = (byte)compressionAlgo;
		if(encryptedHeaders.length != y)
			throw new IllegalStateException("Have more bytes to generate encoding SSK");
		aes.initialize(cryptoKey);
		pcfb.reset(ehDocname);
		pcfb.blockEncipher(encryptedHeaders, 0, encryptedHeaders.length);
		System.arraycopy(encryptedHeaders, 0, headers, x, encryptedHeaders.length);
		x+=encryptedHeaders.length;
		// Generate implicit overall hash.
		md256.update(headers, 0, x);
		md256.update(encryptedDataHash);
		byte[] overallHash = md256.digest();
		// Now sign it
		DSASignature sig = DSA.sign(pubKey.getGroup(), privKey, new NativeBigInteger(1, overallHash), r);
		// Pack R and S into 32 bytes each, and copy to headers.
		
		// Then create and return the ClientSSKBlock.
		byte[] rBuf = truncate(sig.getR().toByteArray(), ClientSSKBlock.SIG_R_LENGTH);
		byte[] sBuf = truncate(sig.getS().toByteArray(), ClientSSKBlock.SIG_S_LENGTH);
		System.arraycopy(rBuf, 0, headers, x, rBuf.length);
		x+=rBuf.length;
		System.arraycopy(sBuf, 0, headers, x, sBuf.length);
		x+=sBuf.length;
		if(x != SSKBlock.TOTAL_HEADERS_LENGTH)
			throw new IllegalStateException("Too long");
		try {
			return new ClientSSKBlock(data, headers, this, false); // FIXME set last arg to true to not verify
		} catch (SSKVerifyException e) {
			IllegalStateException exception=new IllegalStateException("Impossible encoding error: "+e.getMessage());
			exception.initCause(e);

			throw exception;
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
			byte[] buf = new byte[len];
			System.arraycopy(bs, (bs.length-len), buf, 0, len);
			return buf;
		}
	}

	public static InsertableClientSSK createRandom(RandomSource r) {
		byte[] ckey = new byte[CRYPTO_KEY_LENGTH];
		r.nextBytes(ckey);
		DSAGroup g = Global.DSAgroupBigA;
		DSAPrivateKey privKey = new DSAPrivateKey(g, r);
		DSAPublicKey pubKey = new DSAPublicKey(g, privKey);
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		return new InsertableClientSSK("", md.digest(pubKey.asBytes()), pubKey, privKey, ckey);
	}

	public FreenetURI getInsertURI() {
		return new FreenetURI("SSK", docName, privKey.getX().toByteArray(), cryptoKey, null);
	}
	
}
