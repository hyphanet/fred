package freenet.crypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import freenet.crypt.JceLoader;
import freenet.node.FSParseException;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.DerUtils;

import org.bouncycastle.jce.interfaces.ECPointEncoder;

public class ECDSA {
    public final Curves curve;
    private final KeyPair key;
	private final byte[] compressedPubkey;

    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        // Order matters. Append to the list, do not re-order.
        P256("secp256r1", "SHA256withECDSA", 59, 36, 64,
		// 301306072a8648ce3d020106082a8648ce3d030107
		new byte [] {
		    (byte)0x30,// 2:d=1  hl=2 l=19 cons: SEQUENCE
		    (byte)0x13,
		    (byte)0x06,// 4:d=2  hl=2 l= 7 prim: OBJECT :id-ecPublicKey
		    (byte)0x07,
		    (byte)0x2a, (byte)0x86, (byte)0x48, (byte)0xce,
		    (byte)0x3d, (byte)0x02, (byte)0x01,
		    (byte)0x06,//13:d=2  hl=2 l= 8 prim: OBJECT :prime256v1
		    (byte)0x08,
		    (byte)0x2a, (byte)0x86, (byte)0x48, (byte)0xce,
		    (byte)0x3d, (byte)0x03, (byte)0x01, (byte)0x07,
		}),
        P384("secp384r1", "SHA384withECDSA", 72, 52, 96,
		// 301006072a8648ce3d020106052b81040022
		new byte [] {
		    (byte)0x30,// 2:d=1  hl=2 l=16 cons: SEQUENCE
		    (byte)0x10,
		    (byte)0x06,// 4:d=2  hl=2 l= 7 prim: OBJECT :id-ecPublicKey
		    (byte)0x07,
		    (byte)0x2a, (byte)0x86, (byte)0x48, (byte)0xce,
		    (byte)0x3d, (byte)0x02, (byte)0x01,
		    (byte)0x06,//13:d=2  hl=2 l= 5 prim: OBJECT :prime384v1
		    (byte)0x05,
		    (byte)0x2b, (byte)0x81, (byte)0x04, (byte)0x00, (byte)0x22,
		}),
        P521("secp521r1", "SHA512withECDSA", 90, 70, 132,
		// 301006072a8648ce3d020106052b81040023
		new byte [] {
		    (byte)0x30,// 2:d=1  hl=2 l=16 cons: SEQUENCE
		    (byte)0x10,
		    (byte)0x06,// 4:d=2  hl=2 l= 7 prim: OBJECT :id-ecPublicKey
		    (byte)0x07,
		    (byte)0x2a, (byte)0x86, (byte)0x48, (byte)0xce,
		    (byte)0x3d, (byte)0x02, (byte)0x01,
		    (byte)0x06,//13:d=2  hl=2 l= 5 prim: OBJECT :prime521v1
		    (byte)0x05,
		    (byte)0x2b, (byte)0x81, (byte)0x04, (byte)0x00, (byte)0x23,
		});
        
        public final ECGenParameterSpec spec;
        private final KeyPairGenerator keygen;
        /** The hash algorithm used to generate the signature */
        public final String defaultHashAlgorithm;
        /** Expected size of a DER encoded compressed pubkey in bytes */
        public final int modulusSize;
        /** Expected size of a raw compressed pubkey */
        public final int modulusRawSize;
		/** Expected size of CVC-encoded signature */
		public final int signatureSize;
		/** Fixed header of DER-encoded pubkey */
		public final byte [] derPubkeyHeader;

		protected final Provider kgProvider;
		protected final Provider kfProvider;
		protected final Provider sigProvider;
		protected final boolean sigAcceptConvertedKeys;

        /** Verify KeyPairGenerator and KeyFactory work correctly */
        static private KeyPair selftest(KeyPairGenerator kg, KeyFactory kf)
            throws InvalidKeySpecException
        {
            KeyPair key = kg.generateKeyPair();
            PublicKey pub = key.getPublic();
            PrivateKey pk = key.getPrivate();
            byte [] pubkey = pub.getEncoded();
            byte [] pkey = pk.getEncoded();
            PublicKey pub2 = kf.generatePublic(
                    new X509EncodedKeySpec(pubkey)
                    );
            if(!Arrays.equals(pub2.getEncoded(), pubkey))
                throw new Error("Pubkey encoding mismatch");
            PrivateKey pk2 = kf.generatePrivate(
                    new PKCS8EncodedKeySpec(pkey)
                    );
			/*
            if(!Arrays.equals(pk2.getEncoded(), pkey))
                throw new Error("Pubkey encoding mismatch");
			*/
            return key;
        }

		static private PublicKey selftest_compressed(KeyFactory kf, KeyPair key, int modulusSize)
            throws InvalidKeySpecException, NoSuchAlgorithmException
		{
			PublicKey pub = key.getPublic();
			KeyFactory kfBC = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
			if (!(pub instanceof ECPointEncoder)) {
				pub = kfBC.generatePublic(new X509EncodedKeySpec(pub.getEncoded()));
			}
			if (!(pub instanceof ECPointEncoder))
				throw new Error("Converted pubkey still incompatible with "+ECPointEncoder.class);
			((ECPointEncoder)pub).setPointFormat("COMPRESSED");
            byte [] cpubkey = pub.getEncoded();
			if(cpubkey.length != modulusSize)
				throw new Error("Unexpected pubkey length: "+cpubkey.length+"!="+modulusSize);
			PublicKey cpub = kfBC.generatePublic(new X509EncodedKeySpec(cpubkey));
			((ECPointEncoder)cpub).setPointFormat("UNCOMPRESSED");
			if(kf.getProvider() != JceLoader.BouncyCastle)
				kf.generatePublic(new X509EncodedKeySpec(cpub.getEncoded())).getEncoded();
			return cpub;
		}

		static private void selftest_sign(KeyPair key, PublicKey pub, Signature sig)
			throws SignatureException, InvalidKeyException
		{
			sig.initSign(key.getPrivate());
			byte[] sign = sig.sign();
			sig.initVerify(pub);
			boolean verified = sig.verify(sign);
			if (!verified)
				throw new Error("Verification failed");
		}

        private Curves(String name, String defaultHashAlgorithm, int modulusSize, int modulusRawSize, int signatureSize, byte [] derPubkeyHeader) {
            this.spec = new ECGenParameterSpec(name);
			Signature sig = null;
			KeyFactory kf = null;
            KeyPairGenerator kg = null;
			// Ensure providers loaded
			JceLoader.BouncyCastle.toString();
			boolean sigAcceptConvertedKeys = false;
			try {
				KeyPair key = null;
				PublicKey cpub = null;
				try {
					/* check if default EC keys work correctly */
					kg = KeyPairGenerator.getInstance("EC");
					kf = KeyFactory.getInstance("EC");
					kg.initialize(this.spec);
					key = selftest(kg, kf);
					cpub = selftest_compressed(kf, key, modulusSize);
				} catch(Throwable e) {
					/* we don't care why we fail, just fallback */
					Logger.warning(this, "default KeyPairGenerator provider ("+(kg != null ? kg.getProvider() : null)+") is broken, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = selftest(kg, kf);
					cpub = selftest_compressed(kf, key, modulusSize);
				}
				try {
					/* check default Signature compatible with kf/kg */
					sig = Signature.getInstance(defaultHashAlgorithm);
					selftest_sign(key, key.getPublic(), sig);
					try {
						/*
						 * check if sigProvider works with converted keys,
						 * generated by BouncyCastle KeyFactory
						 */
						selftest_sign(key, cpub, sig);
						sigAcceptConvertedKeys = true;
					} catch(Throwable e) {
						/* sigAcceptConvertedKeys = false */
					}
				} catch(Throwable e) {
					/* we don't care why we fail, just fallback */
					Logger.warning(this, "default Signature provider ("+(sig != null ? sig.getProvider() : null)+") is broken or incompatible with KeyPairGenerator, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = kg.generateKeyPair();
					sig = Signature.getInstance(defaultHashAlgorithm, JceLoader.BouncyCastle);
					selftest_sign(key, key.getPublic(), sig);
					sigAcceptConvertedKeys = true;
				}
            } catch (NoSuchAlgorithmException e) {
                Logger.error(ECDSA.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                Logger.error(ECDSA.class, "InvalidAlgorithmParameterException : "+e.getMessage(),e);
                e.printStackTrace();
            } catch (InvalidKeyException e) {
				throw new Error(e);
            } catch (InvalidKeySpecException e) {
				throw new Error(e);
            } catch (SignatureException e) {
				throw new Error(e);
            }
			if (!sigAcceptConvertedKeys) {
				Logger.warning(this, "Signature does not accept converted keys, use 2-stage conversion");
			}
			this.sigAcceptConvertedKeys = sigAcceptConvertedKeys;
			this.kgProvider = kg.getProvider();
			this.kfProvider = kf.getProvider();
			this.sigProvider = sig.getProvider();
            this.keygen = kg;
            this.defaultHashAlgorithm = defaultHashAlgorithm;
            this.modulusSize = modulusSize;
            this.modulusRawSize = modulusRawSize;
			this.signatureSize = signatureSize;
			this.derPubkeyHeader = derPubkeyHeader;
            Logger.normal(this, name +": using "+kgProvider+" for KeyPairGenerator(EC)");
            Logger.normal(this, name +": using "+kfProvider+" for KeyFactory(EC)");
            Logger.normal(this, name +": using "+sigProvider+" for Signature("+defaultHashAlgorithm+")");

        }
        
        public synchronized KeyPair generateKeyPair() {
            return keygen.generateKeyPair();
        }
        
		public byte[] getPublicKeyNetworkFormat(PublicKey pub) {
			if (!(pub instanceof ECPointEncoder)) {
				try {
					pub = KeyFactory.getInstance("EC", JceLoader.BouncyCastle).
						generatePublic(new X509EncodedKeySpec(pub.getEncoded()));
				} catch(NoSuchAlgorithmException e) {
					new Error(e); // impossible
				} catch(InvalidKeySpecException e) {
					new Error(e); // impossible
				}
				if (!(pub instanceof ECPointEncoder))
					throw new Error("BouncyCastle generated pubkey that is not instance of "+ECPointEncoder.class);
			}
			byte raw[];
			synchronized(pub) {
				((ECPointEncoder)pub).setPointFormat("COMPRESSED");
				raw = pub.getEncoded();
			}
			try {
				raw = DerUtils.DerECPubkeyToRAW(derPubkeyHeader, raw, true);
			} catch(IllegalArgumentException e) {
				// other side can accept both format variation
				Logger.warning(ECDSA.class, "Cannot unwrap pubkey: "+e, e);
				System.err.println("unwrap: "+e);
				e.printStackTrace();
			} catch(ArrayIndexOutOfBoundsException e) {
				Logger.warning(ECDSA.class, "Cannot unwrap pubkey: "+e, e);
				System.err.println("unwrap: "+e);
				e.printStackTrace();
			}
			return raw;
		}

        public SimpleFieldSet getSFS(ECPublicKey pub) {
            SimpleFieldSet ecdsaSFS = new SimpleFieldSet(true);
            SimpleFieldSet curveSFS = new SimpleFieldSet(true);
            curveSFS.putSingle("pub", Base64.encode(getPublicKeyNetworkFormat(pub)));
            ecdsaSFS.put(name(), curveSFS);
            return ecdsaSFS;
        }

        public String toString() {
            return spec.getName();
        }
    }
    
    /**
     * Initialize the ECDSA object: this will draw some entropy
     * @param curve
     */
    public ECDSA(Curves curve) {
        this.curve = curve;
		this.key = curve.keygen.generateKeyPair();
		this.compressedPubkey = curve.getPublicKeyNetworkFormat(key.getPublic());
    }
    
    /**
     * Initialize the ECDSA object: from an SFS generated by asFieldSet()
     * @param curve
     * @throws FSParseException 
     */
    public ECDSA(SimpleFieldSet sfs, Curves curve) throws FSParseException {
        byte[] pri = null;
        try {
            ECPublicKey pubK = getPublicKey(sfs, curve);
            pri = Base64.decode(sfs.get("pri"));
            PKCS8EncodedKeySpec ks = new PKCS8EncodedKeySpec(pri);
            KeyFactory kf = KeyFactory.getInstance("EC", curve.kfProvider);
            ECPrivateKey privK = (ECPrivateKey) kf.generatePrivate(ks);

            this.key = new KeyPair(pubK, privK);
			this.compressedPubkey = curve.getPublicKeyNetworkFormat(pubK);
        } catch (Exception e) {
            throw new FSParseException(e);
        }
        this.curve = curve;
    }
    
    public byte[] sign(byte[]... data) {
        byte[] result = null;
        try {
            Signature sig = Signature.getInstance(curve.defaultHashAlgorithm, curve.sigProvider);
            sig.initSign(key.getPrivate());
			for(byte[] d: data)
				sig.update(d);
            result = sig.sign();
			try {
				result = DerUtils.DerECSignatureToCVC(result, curve.signatureSize);
			} catch(IllegalArgumentException e) {
				Logger.error(this, "Cannot convert EC signature to fixed-size CVC format "+e, e);
				result = null;
			} catch(ArrayIndexOutOfBoundsException e) {
				Logger.error(this, "Cannot convert EC signature to fixed-size CVC format "+e, e);
				result = null;
			}
        } catch (NoSuchAlgorithmException e) {
            Logger.error(this, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            Logger.error(this, "InvalidKeyException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (SignatureException e) {
            Logger.error(this, "SignatureException : "+e.getMessage(),e);
            e.printStackTrace();
        }
        
        return result;
    }
    
    public boolean verify(byte[] signature, byte[]... data) {
        return verify(curve, getPublicKey(), signature, data);
    }
    
    public boolean verify(byte[] signature, int sigoffset, int siglen, byte[]... data) {
        return verify(curve, getPublicKey(), signature, sigoffset, siglen, data);
    }
    
    public static boolean verify(Curves curve, ECPublicKey key, byte[] signature, byte[]... data) {
		return verify(curve, key, signature, 0, signature.length, data);
	}

    public static boolean verify(Curves curve, ECPublicKey key, byte[] signature, int sigoffset, int siglen,  byte[]... data) {
        if(key == null || curve == null || signature == null || data == null)
            return false;
        boolean result = false;
        try {
			if (siglen == curve.signatureSize) {
				signature = DerUtils.DerECSignatureFromCVC(signature, sigoffset, siglen);
				sigoffset = 0;
				siglen = signature.length;
			}
            Signature sig = Signature.getInstance(curve.defaultHashAlgorithm, curve.sigProvider);
            sig.initVerify(key);
			for(byte[] d: data)
				sig.update(d);
            result = sig.verify(signature, sigoffset, siglen);
        } catch (NoSuchAlgorithmException e) {
            Logger.error(ECDSA.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            Logger.error(ECDSA.class, "InvalidKeyException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (SignatureException e) {
            Logger.error(ECDSA.class, "SignatureException : "+e.getMessage(),e);
            e.printStackTrace();
        }
        return result;
    }
    
    public ECPublicKey getPublicKey() {
        return (ECPublicKey) key.getPublic();
    }
    
    public byte[] getPublicKeyNetworkFormat() {
        return compressedPubkey;
    }
    
    /**
     * Returns an ECPublicKey from bytes obtained using ECPublicKey.getEncoded()
     * @param data
     * @return ECPublicKey or null if it fails
     */
    public static ECPublicKey getPublicKey(byte[] data, Curves curve) {
        ECPublicKey remotePublicKey = null;
		if (data[0] == (byte)0x30) { // SEQUENCE: full pubkey
			/* Do nothing */
		} else if (data[0] == (byte)0x03) { // BITSTRING: stripped pubkey
			try {
				data = DerUtils.DerECPubkeyFromRAW(curve.derPubkeyHeader, data, true);
			} catch(IllegalArgumentException e) {
				Logger.warning(ECDSA.class, "Cannot rewrap pubkey: "+e, e);
				System.err.println("unwrap: "+e);
				e.printStackTrace();
				return null;
			} catch(ArrayIndexOutOfBoundsException e) {
				Logger.warning(ECDSA.class, "Cannot rewrap pubkey: "+e, e);
				System.err.println("unwrap: "+e);
				e.printStackTrace();
				return null;
			}
		} else {
			System.err.println("unexpected: "+data[0]);
			return null;
		}
        try {
            X509EncodedKeySpec ks = new X509EncodedKeySpec(data);
            KeyFactory kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
            remotePublicKey = (ECPublicKey)kf.generatePublic(ks);
			if (!curve.sigAcceptConvertedKeys) {
				((ECPointEncoder)remotePublicKey).setPointFormat("UNCOMPRESSED");
				ks = new X509EncodedKeySpec(remotePublicKey.getEncoded());
				kf = KeyFactory.getInstance("EC", curve.kfProvider);
				remotePublicKey = (ECPublicKey)kf.generatePublic(ks);
			}
        } catch (NoSuchAlgorithmException e) {
            Logger.error(ECDSA.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            Logger.error(ECDSA.class, "InvalidKeySpecException : "+e.getMessage(), e);
            e.printStackTrace();
        }
        
        return remotePublicKey;
    }
    
    public static ECPublicKey getPublicKey(SimpleFieldSet sfs, Curves curve) throws FSParseException {
        try {
			byte[] pub = Base64.decode(sfs.get("pub"));
			/*
            if (pub.length != curve.modulusSize)
                throw new InvalidKeyException();
			*/
			return getPublicKey(pub, curve);
        } catch (Exception e) {
            throw new FSParseException(e);
		}
	}

    /**
     * Returns an SFS containing:
     *  - the private key
     *  - the public key
     *  - the name of the curve in use
     *  
     *  It should only be used in NodeCrypto
     * @param includePrivate - include the (secret) private key
     * @return SimpleFieldSet
     */
    public synchronized SimpleFieldSet asFieldSet(boolean includePrivate) {
        SimpleFieldSet fs = new SimpleFieldSet(true);
        SimpleFieldSet fsCurve = new SimpleFieldSet(true);
		byte[] raw = compressedPubkey;
        fsCurve.putSingle("pub", Base64.encode(raw));
        if(includePrivate)
            fsCurve.putSingle("pri", Base64.encode(key.getPrivate().getEncoded()));
        fs.put(curve.name(), fsCurve);
        return fs;
    }
}
