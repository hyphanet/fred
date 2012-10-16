package freenet.crypt;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;

import freenet.support.Logger;

public class ECDH {

    public final Curves curve;
    private final KeyPair key;
    
    public enum Curves {
        // rfc5903 or rfc6460: it's NIST's random/prime curves : suite B
        P256("secp256r1", 91, "AES128", 1),
        P384("secp384r1",120, "AES192", 2),
        P521("secp521r1",158, "AES256", 3);
        
        public final ECGenParameterSpec spec;
        private final KeyPairGenerator keygen;
        /* The modulus size in bytes associated with the current curve */
        public final int modulusSize;
        /* The symmetric algorithm associated with the curve */
        public final String defaultKeyAlgorithm;
        /* The Freenet-specific index identifying the curve */
        public final byte index;
        
        private Curves(String name, int modulusSize, String defaultKeyAlg, int index) {
            this.spec = new ECGenParameterSpec(name);
            KeyPairGenerator kg = null;
            try {
                kg = KeyPairGenerator.getInstance("ECDH");
                kg.initialize(spec);
            } catch (NoSuchAlgorithmException e) {
                Logger.error(ECDH.class, "NoSuchAlgorithmException : "+e.getMessage(),e);
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                Logger.error(ECDH.class, "InvalidAlgorithmParameterException : "+e.getMessage(),e);
                e.printStackTrace();
            }
            this.keygen = kg;
            this.modulusSize = modulusSize;
            this.defaultKeyAlgorithm = defaultKeyAlg;
            this.index = (byte) index;
        }
        
        public synchronized KeyPair generateKeyPair() {
            return keygen.generateKeyPair();
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
        this.key = curve.keygen.generateKeyPair();
    }
    
    /**
     * Completes the ECDH exchange: this is CPU intensive
     * @param pubkey
     * @return a SecretKey or null if it fails
     * 
     * **THE OUTPUT SHOULD ALWAYS GO THROUGH A KDF**
     */
    public SecretKey getAgreedSecret(ECPublicKey pubkey) {
        SecretKey key = null;
        try {
            key = getAgreedSecret(pubkey, curve.defaultKeyAlgorithm);
        } catch (InvalidKeyException e) {
            Logger.error(this, "InvalidKeyException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            Logger.error(this, "NoSuchAlgorithmException : "+e.getMessage(),e);
            e.printStackTrace();
        } catch (InvalidAlgorithmParameterException e) {
            Logger.error(this, "InvalidAlgorithmParameterException : "+e.getMessage(),e);
            e.printStackTrace();
        }
        return key;
    }
    
    protected SecretKey getAgreedSecret(PublicKey pubkey, String algorithm) throws InvalidKeyException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        KeyAgreement ka = null;
        ka = KeyAgreement.getInstance("ECDH");
        ka.init(key.getPrivate(), curve.spec);
        ka.doPhase(pubkey, true);
        
        return ka.generateSecret(algorithm);        
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
            KeyFactory kf = KeyFactory.getInstance("ECDH");
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
}
