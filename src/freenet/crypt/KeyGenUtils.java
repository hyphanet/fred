/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.security.GeneralSecurityException;
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

import freenet.support.Logger;

/**
 * KeyGenUtils offers a set of methods to easily generate Keys and KeyPairs for 
 * specific algorithms as well as for generating IVs and nonces. It will also take
 * keys stored in byte arrays and put them in SecertKey or KeyPair instances.
 * @author unixninja92
 *
 */
public class KeyGenUtils {
	
	/**
	 * Generates a public/private key pair formated for the algorithm specified
	 * and stores the keys in a KeyPair. Can not handle DSA keys.
	 * @param type The algorithm format that the key pair should be generated for.
	 * @return Returns the generated key pair
	 * @throws UnsupportedTypeException 
	 */
	public static KeyPair genKeyPair(KeyPairType type) throws UnsupportedTypeException{
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
	 * @throws UnsupportedTypeException 
	 */
	public static PublicKey getPublicKey(KeyPairType type, byte[] pub) throws UnsupportedTypeException{
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
	 * Converts a specified key for a specified algorithm to a PublicKey which is then stored in
	 * a KeyPair. The private key of the KeyPair is null. Can not handle DSA keys.
	 * @param type The type of key being passed in
	 * @param pub Public key as byte[]
	 * @return Public key as KeyPair with a null private key
	 * @throws UnsupportedTypeException 
	 */
	public static KeyPair getPublicKeyPair(KeyPairType type, byte[] pub) throws UnsupportedTypeException{
		return getKeyPair(getPublicKey(type, pub), null);
	}
	
	/**
	 * Converts the specified keys for a specified algorithm to PrivateKey and PublicKey
	 * respectively. These are then placed in a KeyPair. Can not handle DSA keys.
	 * @param type The type of key being passed in
	 * @param pub Public key as byte[]
	 * @param pri Private key as byte[]
	 * @return The public key and private key in a KeyPair
	 * @throws UnsupportedTypeException 
	 */
	public static KeyPair getKeyPair(KeyPairType type, byte[] pub, byte[] pri) throws UnsupportedTypeException{
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
	 * Converts the specified key into a SecretKey for the specified algorithm
	 * @param key The byte[] of the key
	 * @param type Type of key
	 * @return The key as a SecretKey
	 */
	public static SecretKey getSecretKey(byte[] key, KeyType type){
		return new SecretKeySpec(key, type.alg);
	}
	
	/**
	 * Generates a random nonce of a specified length
	 * @param length How long the nonce should be
	 * @return The randomly generated nonce
	 */
	public static byte[] genNonce(int length){
		byte[] nonce = new byte[length];
		Util.sRandom.nextBytes(nonce);
		return nonce;
	}
	
	/**
	 * Generates a random iv of a specified length
	 * @param length How long the iv should be
	 * @return The randomly generated iv
	 */
	public static IvParameterSpec genIV(int length){
		return new IvParameterSpec(genNonce(length));
	}
	
	/**
	 * Converts an iv in a specified portion of a byte[] and places it 
	 * in a IvParameterSpec.
	 * @param iv The byte[] containing the iv
	 * @param offset Where the iv begins 
	 * @param length How long the iv is
	 * @return Returns an IvParameterSpec containing the iv. 
	 */
	public static IvParameterSpec getIvParameterSpec(byte[] iv, int offset, int length){
		return new IvParameterSpec(iv, offset, length);
	}
}
