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
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;

import freenet.crypt.JceLoader;
import freenet.support.Logger;
import freenet.support.DerUtils;

import org.bouncycastle.jce.interfaces.ECPointEncoder;

public class ECDH {

    public final Curves curve;
    private final KeyPair key;
	private final byte[] compressedPubkey;
    
    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        // Order matters. Append to the list, do not re-order.
        P256("secp256r1", 91, 59, 36, 32,
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
        P384("secp384r1", 120, 72, 52, 48,
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
        P521("secp521r1", 158, 90, 70, 66,
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
        private KeyPairGenerator keygenCached;

        protected final Provider kgProvider;
        protected final Provider kfProvider;
        protected final Provider kaProvider;
		protected final boolean kaAcceptConvertedKeys;
        
        /** Expected size of a pubkey */
        public final int modulusSize;
        /** Expected size of a compressed pubkey */
        public final int compressedModulusSize;
        /** Expected size of a raw compressed pubkey */
        public final int modulusRawSize;
        /** Expected size of the derived secret (in bytes) */
        public final int derivedSecretSize;
		/** Fixed header of DER-encoded pubkey */
		public final byte [] derPubkeyHeader;
        
        /** Verify KeyPairGenerator and KeyFactory work correctly */
        static private KeyPair selftest(KeyPairGenerator kg, KeyFactory kf, int modulusSize)
            throws InvalidKeySpecException
        {
            KeyPair key = kg.generateKeyPair();
            PublicKey pub = key.getPublic();
            PrivateKey pk = key.getPrivate();
			if (pub instanceof ECPointEncoder)
				((ECPointEncoder)pub).setPointFormat("UNCOMPRESSED");
            byte [] pubkey = pub.getEncoded();
            byte [] pkey = pk.getEncoded();
			if(pubkey.length != modulusSize)
				throw new Error("Unexpected pubkey length: "+pubkey.length+"!="+modulusSize);
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

		static private void selftest_genSecret(KeyPair key, PublicKey pub, KeyAgreement ka)
			throws InvalidKeyException
		{
			ka.init(key.getPrivate());
			ka.doPhase(pub, true);
			ka.generateSecret();
		}

        private Curves(String name, int modulusSize, int compressedModulusSize, int modulusRawSize, int derivedSecretSize, byte [] derPubkeyHeader) {
            this.spec = new ECGenParameterSpec(name);
            KeyAgreement ka = null;
			KeyFactory kf = null;
            KeyPairGenerator kg = null;
			// Ensure providers loaded
			JceLoader.BouncyCastle.toString();
			boolean kaAcceptConvertedKeys = false;
			try {
				KeyPair key = null;
				PublicKey cpub = null;
				try {
					/* check if default EC keys work correctly */
					kg = KeyPairGenerator.getInstance("EC");
					kf = KeyFactory.getInstance("EC");
					kg.initialize(this.spec);
					key = selftest(kg, kf, modulusSize);
					cpub = selftest_compressed(kf, key, compressedModulusSize);
				} catch(Throwable e) {
					// we don't care *why* we fail
					Logger.warning(this, "default KeyPairGenerator provider ("+(kg != null ? kg.getProvider() : null)+") is broken, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = selftest(kg, kf, modulusSize);
					cpub = selftest_compressed(kf, key, compressedModulusSize);
				}
				try {
					/* check default KeyAgreement compatible with kf/kg */
					ka = KeyAgreement.getInstance("ECDH");
					selftest_genSecret(key, key.getPublic(), ka);
					try {
						/*
						 * check if kaProvider works with converted keys,
						 * generated by BouncyCastle KeyFactory
						 */
						selftest_genSecret(key, cpub, ka);
						kaAcceptConvertedKeys = true;
					} catch(Throwable e) {
						/* kaAcceptConvertedKeys = false */
					}
				} catch(Throwable e) {
					// we don't care *why* we fail
					Logger.warning(this, "default KeyAgreement provider ("+(ka != null ? ka.getProvider() : null)+") is broken or incompatible with KeyPairGenerator, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = kg.generateKeyPair();
					ka = KeyAgreement.getInstance("ECDH", JceLoader.BouncyCastle);
					selftest_genSecret(key, key.getPublic(), ka);
					kaAcceptConvertedKeys = true;
				}
			} catch(NoSuchAlgorithmException e) {
				System.out.println(e);
				e.printStackTrace(System.out);
			} catch(InvalidKeySpecException e) {
				System.out.println(e);
				e.printStackTrace(System.out);
			} catch(InvalidKeyException e) {
				System.out.println(e);
				e.printStackTrace(System.out);
			} catch(InvalidAlgorithmParameterException e) {
				System.out.println(e);
				e.printStackTrace(System.out);
			}
			if (!kaAcceptConvertedKeys) {
				Logger.warning(this, "KeyAgreement does not accept converted keys, use 2-stage conversion");
			}
			this.modulusSize = modulusSize;
			this.derivedSecretSize = derivedSecretSize;
			this.modulusRawSize = modulusRawSize;
			this.compressedModulusSize = compressedModulusSize;
			this.kaAcceptConvertedKeys = kaAcceptConvertedKeys;
			this.derPubkeyHeader = derPubkeyHeader;
			this.kgProvider = kg.getProvider();
			this.kfProvider = kf.getProvider();
			this.kaProvider = ka.getProvider();
			Logger.normal(this, name +": using "+kgProvider+" for KeyPairGenerator(EC)");
			Logger.normal(this, name +": using "+kfProvider+" for KeyFactory(EC)");
			Logger.normal(this, name +": using "+kaProvider+" for KeyAgreement(ECDH)");
		}
        
        private synchronized KeyPairGenerator getKeyPairGenerator() {
        	if(keygenCached != null) return keygenCached;
            KeyPairGenerator kg = null;
            try {
                kg = KeyPairGenerator.getInstance("EC", kgProvider);
                kg.initialize(spec);
            } catch (NoSuchAlgorithmException e) {
                Logger.error(ECDH.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                Logger.error(ECDH.class, "InvalidAlgorithmParameterException : "+e.getMessage(),e);
                e.printStackTrace();
            }
            keygenCached = kg;
            return kg;
        }
        
        public synchronized KeyPair generateKeyPair() {
            return getKeyPairGenerator().generateKeyPair();
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
			byte[] raw;
			synchronized(pub) {
				((ECPointEncoder)pub).setPointFormat("COMPRESSED");
				raw = pub.getEncoded();
				((ECPointEncoder)pub).setPointFormat("UNCOMPRESSED");
			}
			try {
				raw = DerUtils.DerECPubkeyToRAW(derPubkeyHeader, raw, true);
			} catch(IllegalArgumentException e) {
				// other side can accept both format variation
				Logger.warning(ECDH.class, "Cannot unwrap pubkey: "+e, e);
			} catch(ArrayIndexOutOfBoundsException e) {
				Logger.warning(ECDH.class, "Cannot unwrap pubkey: "+e, e);
			}
			return raw;
		}
        public String toString() {
            return spec.getName();
        }
    }
    
    /**
     * Initialize the ECDH Exchange: this will draw some entropy
     * @param curve
     */
    public ECDH(Curves curve) {
        this.curve = curve;
        this.key = curve.generateKeyPair();
		this.compressedPubkey = curve.getPublicKeyNetworkFormat(key.getPublic());
    }
    
    /**
     * Completes the ECDH exchange: this is CPU intensive
     * @param pubkey
     * @return a SecretKey or null if it fails
     * 
     * **THE OUTPUT SHOULD ALWAYS GO THROUGH A KDF**
     */
    public byte[] getAgreedSecret(ECPublicKey pubkey) {
        try {
            KeyAgreement ka = null;
            ka = KeyAgreement.getInstance("ECDH", curve.kaProvider);
            ka.init(key.getPrivate());
            ka.doPhase(pubkey, true);

            return ka.generateSecret();
        } catch (InvalidKeyException e) {
            Logger.error(this, "InvalidKeyException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            Logger.error(this, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        }
        return null;
    }
    
    public ECPublicKey getPublicKey() {
        return (ECPublicKey) key.getPublic();
    }

    public byte[] getPublicKeyNetworkFormat(boolean compressed) {
        if (compressed)
			return compressedPubkey;
		PublicKey pubkey = key.getPublic();
		synchronized(pubkey) {
			return pubkey.getEncoded();
		}
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
				Logger.warning(ECDH.class, "Cannot rewrap pubkey: "+e, e);
				return null;
			} catch(ArrayIndexOutOfBoundsException e) {
				Logger.warning(ECDH.class, "Cannot rewrap pubkey: "+e, e);
				return null;
			}
		} else {
			return null;
		}
        try {
            X509EncodedKeySpec ks = new X509EncodedKeySpec(data);
            KeyFactory kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
            remotePublicKey = (ECPublicKey)kf.generatePublic(ks);
			if (!curve.kaAcceptConvertedKeys) {
				((ECPointEncoder)remotePublicKey).setPointFormat("UNCOMPRESSED");
				ks = new X509EncodedKeySpec(remotePublicKey.getEncoded());
				kf = KeyFactory.getInstance("EC", curve.kfProvider);
				remotePublicKey = (ECPublicKey)kf.generatePublic(ks);
			}
        } catch (NoSuchAlgorithmException e) {
            Logger.error(ECDH.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            Logger.error(ECDH.class, "InvalidKeySpecException : "+e.getMessage(), e);
            e.printStackTrace();
        }
        
        return remotePublicKey;
    }

    /** Initialize the key pair generators, which in turn will create the
     * global SecureRandom, which may block waiting for entropy from
     * /dev/random on unix-like systems. So this should be called on startup
     * during the "may block for entropy" stage. Note that because this can
     * block, we still have to do lazy initialisation: We do NOT want to
     * have it blocking *at time of loading the classes*, as this will
     * likely appear as the node completely failing to load. Running this
     * after fproxy has started, with a warning timer, allows us to tell
     * the user what is going on if it takes a while. */
    public static void blockingInit() {
    	Curves.P256.getKeyPairGenerator();
    	// Not used at present.
    	// Anyway Bouncycastle uses a single PRNG.
    	// If these use separate PRNGs, we need to init them explicitly.
    	//Curves.P384.getKeyPairGenerator();
    	//Curves.P521.getKeyPairGenerator();
    }
}
