/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import freenet.node.NodeStarter;
import freenet.support.Logger;

/**
 * KeyGenUtils offers a set of methods to easily generate Keys and KeyPairs for 
 * specific algorithms as well as for generating IVs and nonces. It will also take
 * keys stored in byte arrays and put them in SecertKey or KeyPair instances.
 * @author unixninja92
 *
 */
public final class KeyGenUtils {

    /**
     * Generates a public/private key pair formated for the algorithm specified
     * and stores the keys in a KeyPair. Can not handle DSA keys.
     * @param type The algorithm format that the key pair should be generated for.
     * @return Returns the generated key pair
     */
    public static KeyPair genKeyPair(KeyPairType type) {
        if(type.equals(KeyPairType.DSA)){
            throw new UnsupportedTypeException(type);
        }
        try {
            KeyPairGenerator kg = KeyPairGenerator.getInstance(type.alg);
            kg.initialize(type.spec);
            return kg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        } 
        return null;
    }

    /**
     * Converts a specified key for a specified algorithm to a PublicKey. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as byte[]
     * @return Public key as PublicKey
     */
    public static PublicKey getPublicKey(KeyPairType type, byte[] pub){
        if(type.equals(KeyPairType.DSA)){
            throw new UnsupportedTypeException(type);
        }
        try {
            KeyFactory kf = KeyFactory.getInstance(type.alg);
            X509EncodedKeySpec xks = new X509EncodedKeySpec(pub);
            return kf.generatePublic(xks);
        } catch (GeneralSecurityException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        }
        return null;
    }

    /**
     * Converts a specified key for a specified algorithm to a PublicKey. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as ByteBuffer
     * @return Public key as PublicKey
     */
    public static PublicKey getPublicKey(KeyPairType type, ByteBuffer pub){
        return getPublicKey(type, pub.array());
    }

    /**
     * Converts a specified key for a specified algorithm to a PublicKey which is then stored in
     * a KeyPair. The private key of the KeyPair is null. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as byte[]
     * @return Public key as KeyPair with a null private key
     */
    public static KeyPair getPublicKeyPair(KeyPairType type, byte[] pub) {
        return getKeyPair(getPublicKey(type, pub), null);
    }

    /**
     * Converts a specified key for a specified algorithm to a PublicKey which is then stored in
     * a KeyPair. The private key of the KeyPair is null. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as ByteBuffer
     * @return Public key as KeyPair with a null private key
     */
    public static KeyPair getPublicKeyPair(KeyPairType type, ByteBuffer pub) {
        return  getPublicKeyPair(type, pub.array());
    }

    /**
     * Converts the specified keys for a specified algorithm to PrivateKey and PublicKey
     * respectively. These are then placed in a KeyPair. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as byte[]
     * @param pri Private key as byte[]
     * @return The public key and private key in a KeyPair
     */
    public static KeyPair getKeyPair(KeyPairType type, byte[] pub, byte[] pri) {
        if(type.equals(KeyPairType.DSA)){
            throw new UnsupportedTypeException(type);
        }
        try {
            KeyFactory kf = KeyFactory.getInstance(type.alg);

            PublicKey pubK = getPublicKey(type, pub);

            PKCS8EncodedKeySpec pks = new PKCS8EncodedKeySpec(pri);
            PrivateKey privK = kf.generatePrivate(pks);

            return getKeyPair(pubK, privK);
        } catch (GeneralSecurityException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        } catch (UnsupportedTypeException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        }
        return null;
    }

    /**
     * Converts the specified keys for a specified algorithm to PrivateKey and PublicKey
     * respectively. These are then placed in a KeyPair. Can not handle DSA keys.
     * @param type The type of key being passed in
     * @param pub Public key as ByteBuffer
     * @param pri Private key as ByteBuffer
     * @return The public key and private key in a KeyPair
     */
    public static KeyPair getKeyPair(KeyPairType type, ByteBuffer pub, ByteBuffer pri) {
        return getKeyPair(type, pub.array(), pri.array());
    }

    /**
     * Takes the PublicKey and PrivateKey and stores them in a KeyPair
     * @param pubK Public key as PublicKey
     * @param privK Private key as PrivateKey
     * @return The public key and private key in a KeyPair
     */
    public static KeyPair getKeyPair(PublicKey pubK, PrivateKey privK){
        return new KeyPair(pubK, privK);
    }

    /**
     * Generates a secret key for the specified symmetric algorithm
     * @param type Type of key to generate
     * @return Generated key
     */
    public static SecretKey genSecretKey(KeyType type){
        try{
            KeyGenerator kg = KeyGenerator.getInstance(type.alg);
            kg.init(type.keySize);
            return kg.generateKey();
        } catch (NoSuchAlgorithmException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        }
        return null;
    }

    /**
     * Converts the specified key into a SecretKey for the specified algorithm. Checks the length of
     * the key to make sure it is correct. HMAC does not have a set key length, so any key is 
     * acceptable when using a key of this type.
     * @param key The byte[] of the key
     * @param type Type of key
     * @return The key as a SecretKey
     */
    public static SecretKey getSecretKey(KeyType type, byte[] key){
        if(!type.name().startsWith("HMAC") && key.length != type.keySize >> 3){
            throw new IllegalArgumentException("Key size does not match KeyType");
        }
        return new SecretKeySpec(key, type.alg);
    }

    /**
     * Converts the specified key into a SecretKey for the specified algorithm
     * @param key The ByteBuffer of the key
     * @param type Type of key
     * @return The key as a SecretKey
     */
    public static SecretKey getSecretKey(KeyType type, ByteBuffer key){
        return getSecretKey(type, key.array());
    }

    /**
     * Generates a random byte[] of a specified length
     * @param length How long the byte[] should be
     * @return The randomly generated byte[]
     */
    private static byte[] genRandomBytes(int length){
        byte[] randBytes = new byte[length];
        NodeStarter.getGlobalSecureRandom().nextBytes(randBytes);
        return randBytes;
    }

    /**
     * Generates a random nonce of a specified length
     * @param length How long the nonce should be
     * @return The randomly generated nonce
     */
    public static ByteBuffer genNonce(int length){
        return ByteBuffer.wrap(genRandomBytes(length));
    }

    /**
     * Generates a random iv of a specified length
     * @param length How long the iv should be in bytes
     * @return The randomly generated iv
     */
    public static IvParameterSpec genIV(int length){
        return new IvParameterSpec(genRandomBytes(length));
    }

    /**
     * Converts an iv in a specified portion of a byte[] and places it in a IvParameterSpec.
     * @param iv The byte[] containing the iv
     * @param offset Where the iv begins 
     * @param length How long the iv is
     * @return Returns an IvParameterSpec containing the iv. 
     */
    public static IvParameterSpec getIvParameterSpec(byte[] iv, int offset, int length){
        return new IvParameterSpec(iv, offset, length);
    }

    /**
     * Converts an iv in a ByteBuffer and places it in a IvParameterSpec.
     * @param iv The ByteBuffer containing the iv
     * @return Returns an IvParameterSpec containing the iv. 
     */
    public static IvParameterSpec getIvParameterSpec(ByteBuffer iv){
        return new IvParameterSpec(iv.array());
    }

    /**
     * Derives a ByteBuffer that is 512 bytes long from the given key using the provided class name 
     * and kdfString
     * @param kdfKey The key to derive from
     * @param c Class name to use in derivation 
     * @param kdfString Sting to use in derivation
     * @return A 512 long ByteBuffer
     * @throws InvalidKeyException
     */
    private static ByteBuffer deriveBytes(SecretKey kdfKey, Class<?> c, String kdfString) 
            throws InvalidKeyException{
        if(kdfString == null){
            throw new NullPointerException();
        }
        MessageAuthCode kdf = new MessageAuthCode(MACType.HMACSHA512, kdfKey);
        try {
            return kdf.genMac((c.getName()+kdfString).getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            Logger.error(KeyGenUtils.class, "Internal error; please report:", e);
        }
        return null;
    }


    /**
     * Derives a ByteBuffer of the specified length from the given key using the provided class name 
     * and kdfString
     * @param kdfKey The key to derive from
     * @param c Class name to use in derivation 
     * @param kdfString Sting to use in derivation
     * @param len How long the new ByteBuffer should be. 
     * @return A ByteBuffer of the specified length
     * @throws InvalidKeyException
     */
    private static ByteBuffer deriveBytesTruncated(SecretKey kdfKey, Class<?> c, String kdfString, 
            int len) throws InvalidKeyException{
        byte[] key = new byte[len];
        deriveBytes(kdfKey, c, kdfString).get(key);
        return ByteBuffer.wrap(key);
    }

    /**
     * Derives a SecretKey of the specified type from the given key using the provided class name 
     * and kdfString
     * @param kdfKey The key to derive from
     * @param c Class name to use in derivation 
     * @param kdfString Sting to use in derivation
     * @param type The type of key to derive
     * @return The derived key as a SecretKey
     * @throws InvalidKeyException
     */
    public static SecretKey deriveSecretKey(SecretKey kdfKey, Class<?> c, String kdfString, 
            KeyType type) throws InvalidKeyException{
        return getSecretKey(type, deriveBytesTruncated(kdfKey, c, kdfString, type.keySize >> 3));
    }

    /**
     * Derives a IvParameterSpec of the specified type from the given key using the provided class 
     * name and kdfString
     * @param kdfKey The key to derive from
     * @param c Class name to use in derivation 
     * @param kdfString Sting to use in derivation
     * @param ivType The type of IV to derive
     * @return The derived IV as an IvParameterSpec
     * @throws InvalidKeyException
     */
    public static IvParameterSpec deriveIvParameterSpec(SecretKey kdfKey, Class<?> c, 
            String kdfString, KeyType ivType) throws InvalidKeyException{
        return getIvParameterSpec(deriveBytesTruncated(kdfKey, c, kdfString, ivType.ivSize >> 3));
    }
}
