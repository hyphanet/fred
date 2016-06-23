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

import freenet.node.FSParseException;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

public class ECDSA {
    public final Curves curve;
    private final KeyPair key;

    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        // Order matters. Append to the list, do not re-order.
        P256("secp256r1", "SHA256withECDSA", 91, 72),
        P384("secp384r1", "SHA384withECDSA", 120, 104),
        P521("secp521r1", "SHA512withECDSA", 158, 139);
        
        public final ECGenParameterSpec spec;
        private final KeyPairGenerator keygen;
        /** The hash algorithm used to generate the signature */
        public final String defaultHashAlgorithm;
        /** Expected size of a DER encoded pubkey in bytes */
        public final int modulusSize;
        /** Maximum (padded) size of a DER-encoded signature (network-format) */
        public final int maxSigSize;

		protected final Provider kgProvider;
		protected final Provider kfProvider;
		protected final Provider sigProvider;

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

		static private void selftest_sign(KeyPair key, Signature sig)
			throws SignatureException, InvalidKeyException
		{
			sig.initSign(key.getPrivate());
			byte[] sign = sig.sign();
			sig.initVerify(key.getPublic());
			boolean verified = sig.verify(sign);
			if (!verified)
				throw new Error("Verification failed");
		}

        private Curves(String name, String defaultHashAlgorithm, int modulusSize, int maxSigSize) {
            this.spec = new ECGenParameterSpec(name);
			Signature sig = null;
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
					/* check default Signature compatible with kf/kg */
					sig = Signature.getInstance(defaultHashAlgorithm);
					selftest_sign(key, sig);
				} catch(Throwable e) {
					/* we don't care why we fail, just fallback */
					Logger.warning(this, "default Signature provider ("+(sig != null ? sig.getProvider() : null)+") is broken or incompatible with KeyPairGenerator, falling back to BouncyCastle", e);
					kg = KeyPairGenerator.getInstance("EC", JceLoader.BouncyCastle);
					kf = KeyFactory.getInstance("EC", JceLoader.BouncyCastle);
					kg.initialize(this.spec);
					key = kg.generateKeyPair();
					sig = Signature.getInstance(defaultHashAlgorithm, JceLoader.BouncyCastle);
					selftest_sign(key, sig);
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
			this.kgProvider = kg.getProvider();
			this.kfProvider = kf.getProvider();
			this.sigProvider = sig.getProvider();
            this.keygen = kg;
            this.defaultHashAlgorithm = defaultHashAlgorithm;
            this.modulusSize = modulusSize;
            this.maxSigSize = maxSigSize;
            Logger.normal(this, name +": using "+kgProvider+" for KeyPairGenerator(EC)");
            Logger.normal(this, name +": using "+kfProvider+" for KeyFactory(EC)");
            Logger.normal(this, name +": using "+sigProvider+" for Signature("+defaultHashAlgorithm+")");

        }
        
        public synchronized KeyPair generateKeyPair() {
            return keygen.generateKeyPair();
        }
        
        public SimpleFieldSet getSFS(ECPublicKey pub) {
            SimpleFieldSet ecdsaSFS = new SimpleFieldSet(true);
            SimpleFieldSet curveSFS = new SimpleFieldSet(true);
            curveSFS.putSingle("pub", Base64.encode(pub.getEncoded()));
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
    }
    
    /**
     * Initialize the ECDSA object: from an SFS generated by asFieldSet()
     * @param curve
     * @throws FSParseException 
     */
    public ECDSA(SimpleFieldSet sfs, Curves curve) throws FSParseException {
        byte[] pub = null;
        byte[] pri = null;
        try {
            pub = Base64.decode(sfs.get("pub"));
            if (pub.length > curve.modulusSize)
                throw new InvalidKeyException();
            ECPublicKey pubK = getPublicKey(pub, curve);

            pri = Base64.decode(sfs.get("pri"));
            PKCS8EncodedKeySpec ks = new PKCS8EncodedKeySpec(pri);
            KeyFactory kf = KeyFactory.getInstance("EC", curve.kfProvider);
            ECPrivateKey privK = (ECPrivateKey) kf.generatePrivate(ks);

            this.key = new KeyPair(pubK, privK);
        } catch (Exception e) {
            throw new FSParseException(e);
        }
        this.curve = curve;
    }
    
    public byte[] sign(byte[]... data) {
        byte[] result = null;
        try {
            while(true) {
                // FIXME: we hardcode bouncycastle here because right now that's the only that works
                // verifying with a legacy non-deterministic (SHA256withECDSA) sig
                // will *not* work with a bouncycastle SHA256withECDDSA verifier
                Signature sig = Signature.getInstance(curve.defaultHashAlgorithm.replace("ECDSA", "ECDDSA"), JceLoader.BouncyCastle);
                sig.initSign(key.getPrivate());
    			for(byte[] d: data)
    				sig.update(d);
                result = sig.sign();
                // It's a DER encoded signature, most sigs will fit in N bytes
                // If it doesn't let's re-sign.
                if(result.length <= curve.maxSigSize)
                	break;
                else
                	Logger.error(this, "DER encoded signature used "+result.length+" bytes, more than expected "+curve.maxSigSize+" - re-signing...");
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
    
    /**
     * Sign data and return a fixed size signature. The data does not need to be hashed, the 
     * signing code will handle that for us, using an algorithm appropriate for the keysize.
     * @return A zero padded DER signature (maxSigSize). Space Inefficient but constant-size.
     */
    public byte[] signToNetworkFormat(byte[]... data) {
        byte[] plainsig = sign(data);
        int targetLength = curve.maxSigSize;

        if(plainsig.length != targetLength) {
            byte[] newData = new byte[targetLength];
            if(plainsig.length < targetLength) {
                System.arraycopy(plainsig, 0, newData, 0, plainsig.length);
            } else {
                throw new IllegalStateException("Too long!");
            }
            plainsig = newData;
        }
        return plainsig;
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

    /* Calculates the actual signature length based on the encoded length of the DER sequence
     * contained in the signature. If decoding fails, a SignatureException is thrown. */
    private static int actualSignatureLength(byte[] signature, int sigOff, int sigLen) throws SignatureException {
        // SEQUENCE, universal, constructed
        if (sigLen < 2 || signature[sigOff] != 0x30) {
            throw new SignatureException("Not a sequence");
        }
        int length = signature[1 + sigOff] & 0xFF;
        if (length == 0x80) {
            throw new SignatureException("Indefinite length");
        }
        if (length <= 127) {
            return length + 2;
        }
        final int size = length & 0x7F;
        if (size > 4) {
            throw new SignatureException("Header too big");
        }
        if (sigLen < size + 2) {
            throw new SignatureException("Header out of bounds");
        }
        length = 0;
        for (int i = 0; i < size; i++) {
            length <<= 8;
            length += signature[i + sigOff + 2] & 0xFF;
        }
        if (length < 0) {
            throw new SignatureException("Negative sequence length");
        }
        if (length > sigLen - 2 - size) {
            throw new SignatureException("Sequence out of bounds");
        }
        return length + 2 + size;
    }

    public static boolean verify(Curves curve, ECPublicKey key, byte[] signature, int sigoffset, int siglen,  byte[]... data) {
        if(key == null || curve == null || signature == null || data == null)
            return false;
        boolean result = false;
        try {
            Signature sig = Signature.getInstance(curve.defaultHashAlgorithm, curve.sigProvider);
            sig.initVerify(key);
            for(byte[] d: data)
                sig.update(d);
            // Strip padding: BC 1.54 cannot deal with it.
            siglen = actualSignatureLength(signature, sigoffset, siglen);
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
            Logger.error(ECDSA.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            Logger.error(ECDSA.class, "InvalidKeySpecException : "+e.getMessage(), e);
            e.printStackTrace();
        }
        
        return remotePublicKey;
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
    public SimpleFieldSet asFieldSet(boolean includePrivate) {
        SimpleFieldSet fs = new SimpleFieldSet(true);
        SimpleFieldSet fsCurve = new SimpleFieldSet(true);
        fsCurve.putSingle("pub", Base64.encode(key.getPublic().getEncoded()));
        if(includePrivate)
            fsCurve.putSingle("pri", Base64.encode(key.getPrivate().getEncoded()));
        fs.put(curve.name(), fsCurve);
        return fs;
    }
}
