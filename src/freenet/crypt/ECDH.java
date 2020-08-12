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

import freenet.support.Logger;

public class ECDH {

    public final Curves curve;
    private final KeyPair key;
    
    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        // Order matters. Append to the list, do not re-order.
        P256("secp256r1", 91, 32),
        P384("secp384r1", 120, 48),
        P521("secp521r1", 158, 66);
        
        public final ECGenParameterSpec spec;
        private KeyPairGenerator keygenCached;
        protected final Provider kgProvider;
        protected final Provider kfProvider;
        protected final Provider kaProvider;
        /** Expected size of a pubkey */
        public final int modulusSize;
        /** Expected size of the derived secret (in bytes) */
        public final int derivedSecretSize;
        
        /** Verify KeyPairGenerator and KeyFactory work correctly */
        static private KeyPair selftest(KeyPairGenerator kg, KeyFactory kf, int modulusSize)
            throws InvalidKeySpecException
        {
            KeyPair key = kg.generateKeyPair();
            PublicKey pub = key.getPublic();
            PrivateKey pk = key.getPrivate();
            byte [] pubkey = pub.getEncoded();
            byte [] pkey = pk.getEncoded();
			if(pubkey.length > modulusSize || pubkey.length == 0)
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

		static private void selftest_genSecret(KeyPair key, KeyAgreement ka)
			throws InvalidKeyException
		{
			ka.init(key.getPrivate());
            ka.doPhase(key.getPublic(), true);
            ka.generateSecret();
		}

        private Curves(String name, int modulusSize, int derivedSecretSize) {
            this.spec = new ECGenParameterSpec(name);
            KeyAgreement ka = null;
			KeyFactory kf = null;
            KeyPairGenerator kg = null;
			// Ensure providers loaded
			JceLoader.BouncyCastle.toString();
			try {
				KeyPair key = null;
				try {
					/* check if default EC keys work correctly */
					kg = KeyPairGenerator.getInstance("EC");
					kf = KeyFactory.getInstance("EC");
					kg.initialize(this.spec);
					key = selftest(kg, kf, modulusSize);
				} catch(Throwable e) {
					/* we don't care why we fail, just fallback */
					Logger.warning(this, "default KeyPairGenerator provider ("+(kg != null ? kg.getProvider() : null)+") is broken, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = selftest(kg, kf, modulusSize);
				}
				try {
					/* check default KeyAgreement compatible with kf/kg */
					ka = KeyAgreement.getInstance("ECDH");
					selftest_genSecret(key, ka);
				} catch(Throwable e) {
					/* we don't care why we fail, just fallback */
					Logger.warning(this, "default KeyAgreement provider ("+(ka != null ? ka.getProvider() : null)+") is broken or incompatible with KeyPairGenerator, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					ka = KeyAgreement.getInstance("ECDH", JceLoader.BouncyCastle);
					selftest_genSecret(key, ka);
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
			this.modulusSize = modulusSize;
			this.derivedSecretSize = derivedSecretSize;

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
    
    /**
     * Returns an ECPublicKey from bytes obtained using ECPublicKey.getEncoded()
     * @param data
     * @return ECPublicKey or null if it fails
     */
    public static ECPublicKey getPublicKey(byte[] data, Curves curve) {
        ECPublicKey remotePublicKey = null;
        try {
            X509EncodedKeySpec ks = new X509EncodedKeySpec(data);
            KeyFactory kf = KeyFactory.getInstance("EC", curve.kfProvider);
            remotePublicKey = (ECPublicKey)kf.generatePublic(ks);
            
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

    /** Return the public key as a byte[] in network format */
	public byte[] getPublicKeyNetworkFormat() {
        byte[] ret = getPublicKey().getEncoded();
        if(ret.length == curve.modulusSize) {
        	return ret;
        } else if(ret.length > curve.modulusSize) {
        	throw new IllegalStateException("Encoded public key too long: should be "+curve.modulusSize+" bytes but is "+ret.length);
        } else {
        	Logger.warning(this, "Padding public key from "+ret.length+" to "+curve.modulusSize+" bytes");
        	byte[] out = new byte[curve.modulusSize];
        	System.arraycopy(ret, 0, out, 0, ret.length);
        	return ret;
        }
	}
}
