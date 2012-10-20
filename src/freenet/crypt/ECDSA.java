package freenet.crypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import freenet.support.Logger;

public class ECDSA {

    public final Curves curve;
    private final KeyPair key;

    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        // Order matters. Append to the list, do not re-order.
        P256("secp256r1", "SHA256withECDSA", 91),
        P384("secp384r1", "SHA384withECDSA", 120),
        P521("secp521r1", "SHA512withECDSA", 158);
        
        public final ECGenParameterSpec spec;
        private final KeyPairGenerator keygen;
        /** The hash algorithm used to generate the signature */
        public final String defaultHashAlgorithm;
        /** Expected size of a pubkey (R,S) */
        public final int modulusSize;
        
        private Curves(String name, String defaultHashAlgorithm, int modulusSize) {
            this.spec = new ECGenParameterSpec(name);
            KeyPairGenerator kg = null;
            try {
                kg = KeyPairGenerator.getInstance("ECDSA");
                kg.initialize(spec);
            } catch (NoSuchAlgorithmException e) {
                Logger.error(ECDSA.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                Logger.error(ECDSA.class, "InvalidAlgorithmParameterException : "+e.getMessage(),e);
                e.printStackTrace();
            }
            this.keygen = kg;
            this.defaultHashAlgorithm = defaultHashAlgorithm;
            this.modulusSize = modulusSize;
        }
        
        public synchronized KeyPair generateKeyPair() {
            return keygen.generateKeyPair();
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
    
    public byte[] sign(byte[] data) {
        return sign(data, 0, data.length);
    }

    public byte[] sign(byte[] data, int offset, int len) {
        if(data.length == 0 || data.length < len)
            return null;
        byte[] result = null;
        try {
            Signature sig = Signature.getInstance(curve.defaultHashAlgorithm);
            sig.initSign(key.getPrivate());
            sig.update(data, offset, len);
            result = sig.sign();
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
    
    public boolean verify(byte[] signature, byte[] data) {
        return verify(curve, getPublicKey(), signature, 0, signature.length, data, 0, data.length);
    }
    
    public boolean verify(byte[] signature, int sigoffset, int siglen, byte[] data, int offset, int len) {
        return verify(curve, getPublicKey(), signature, sigoffset, siglen, data, offset, len);
    }
    
    public static boolean verify(Curves curve, ECPublicKey key, byte[] signature, byte[] data) {
        return verify(curve, key, signature, 0, signature.length, data, 0, data.length);
    }
    
    public static boolean verify(Curves curve, ECPublicKey key, byte[] signature, int sigoffset, int siglen, byte[] data, int offset, int len) {
        boolean result = false;
        try {
            Signature sig = Signature.getInstance(curve.defaultHashAlgorithm);
            sig.initVerify(key);
            sig.update(data, offset, len);
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
    public static ECPublicKey getPublicKey(byte[] data) {
        ECPublicKey remotePublicKey = null;
        try {
            X509EncodedKeySpec ks = new X509EncodedKeySpec(data);
            KeyFactory kf = KeyFactory.getInstance("ECDSA");
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
}
