/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.BitSet;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;
import freenet.support.Logger;

/**
 * CryptByteBuffer will encrypt and decrypt both byte[]s and BitSets with a specified
 * algorithm, key, and also an iv if the algorithm requires one. 
 * @author unixninja92
 * 
 * Suggested CryptByteBufferType to use: ChaCha128
 */
@SuppressWarnings("deprecation") // Suppresses warnings about RijndaelPCFB being deprecated
public final class CryptByteBuffer implements Serializable{
    private static final long serialVersionUID = 6143338995971755362L;
    private final CryptByteBufferType type;
    private final SecretKey key;
    private IvParameterSpec iv;

    //Used for AES and ChaCha ciphers
    private Cipher encryptCipher;
    private Cipher decryptCipher;

    //These variables are used with Rijndael ciphers
    private BlockCipher blockCipher;
    private PCFBMode encryptPCFB;
    private PCFBMode decryptPCFB;

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key. If the 
     * algorithm requires an iv, it will either use the one passed in, or if that is
     * null, it will generate a random one.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The iv that will be used for encryption. 
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, SecretKey key, IvParameterSpec iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        if(iv != null && !type.hasIV()){
            throw new UnsupportedTypeException(type, "This type does not take an IV.");
        }
        else if(iv != null){
            this.iv = iv;
        }
        else if(type.hasIV()){
            genIV();
        }

        this.type = type;
        this.key = key;
        try{
            if(type.cipherName.equals("RIJNDAEL")){
                blockCipher = new Rijndael(type.keyType.keySize, type.blockSize);
                blockCipher.initialize(key.getEncoded());
                if(type == CryptByteBufferType.RijndaelPCFB){
                    encryptPCFB = PCFBMode.create(blockCipher, this.iv.getIV());
                    decryptPCFB = PCFBMode.create(blockCipher, this.iv.getIV());
                }
            } else{
                encryptCipher = Cipher.getInstance(type.algName);
                decryptCipher = Cipher.getInstance(type.algName);

                encryptCipher.init(Cipher.ENCRYPT_MODE, this.key, this.iv);
                decryptCipher.init(Cipher.DECRYPT_MODE, this.key, this.iv);
            }
        } catch (UnsupportedCipherException e) {
            e.printStackTrace();
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        }
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key. If the 
     * algorithm requires an iv, it will generate a random one.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, SecretKey key) throws GeneralSecurityException{
        this(type, key, (IvParameterSpec)null);
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key. If the 
     * algorithm requires an iv, it will generate a random one.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, byte[] key) throws GeneralSecurityException{
        this(type, KeyGenUtils.getSecretKey(type.keyType, key));
    }
    
    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key. If the 
     * algorithm requires an iv, it will generate a random one.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, ByteBuffer key) throws GeneralSecurityException{
        this(type, Fields.copyToArray(key));
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv. 
     * The iv will be extracted from the passed in byte[] starting at the offset
     * using the length provided by type.ivSize
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The byte[] containing the iv
     * @param offset Where in the byte[] the iv starts
     * @throws InvalidKeyException
     * @throws InvalidAlgorithmParameterException
     */
    public CryptByteBuffer(CryptByteBufferType type, SecretKey key, byte[] iv, int offset) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, key, new IvParameterSpec(iv, offset, type.ivSize));
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The iv that will be used for encryption. 
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, SecretKey key, byte[] iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, key, iv, 0);
    }
    
    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The iv that will be used for encryption. 
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, SecretKey key, ByteBuffer iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, key, Fields.copyToArray(iv), 0);
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv. 
     * The iv will be extracted from the passed in byte[] starting at the offset
     * using the length provided by type.ivSize
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The byte[] containing the iv
     * @param offset Where in the byte[] the iv starts
     * @throws InvalidKeyException
     * @throws InvalidAlgorithmParameterException
     */
    public CryptByteBuffer(CryptByteBufferType type, byte[] key, byte[] iv, int offset) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, KeyGenUtils.getSecretKey(type.keyType, key), iv, offset);
    }

    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The iv that will be used for encryption. 
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, byte[] key, byte[] iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, key, iv, 0);
    }
    
    /**
     * Creates an instance of CryptByteBuffer that will be able to encrypt and decrypt 
     * sets of bytes using the specified algorithm type with the given key and iv.
     * @param type The symmetric algorithm, mode, and key and block size to use
     * @param key The key that will be used for encryption
     * @param iv The iv that will be used for encryption. 
     * @throws InvalidAlgorithmParameterException 
     * @throws InvalidKeyException 
     */
    public CryptByteBuffer(CryptByteBufferType type, ByteBuffer key, ByteBuffer iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, Fields.copyToArray(key), Fields.copyToArray(iv), 0);
    }

    /**
     * Encrypts the specified section of provided byte[] into a new array returned as a ByteBuffer.
     * Does not modify the original array. If you are using a RijndaelECB alg then len must equal 
     * the block size. 
     * @param input The bytes to be encrypted
     * @param offset The position of input to start encrypting at
     * @param len The number of bytes after offset to encrypt
     * @return Returns ByteBuffer input with the specified section encrypted. The buffer will have
     * a backing array and its array offset will be 0.
     */
    public ByteBuffer encrypt(byte[] input, int offset, int len){
        try{
            if(type == CryptByteBufferType.RijndaelPCFB){
                // RijndaelPCFB will encrypt the original data. We don't want that, so copy.
                if(offset+len > input.length) throw new IllegalArgumentException();
                byte[] buf = Arrays.copyOfRange(input, offset, offset+len);
                encryptPCFB.blockEncipher(buf, 0, len);
                return ByteBuffer.wrap(buf);
            } else if(type.cipherName.equals("RIJNDAEL")){
                byte[] result = new byte[len];
                if(offset+len > input.length) throw new IllegalArgumentException();
                blockCipher.encipher(Arrays.copyOfRange(input, offset, offset+len), result);
                return ByteBuffer.wrap(result);
            }
            else{
                return ByteBuffer.wrap(encryptCipher.doFinal(input, offset, len));
            }
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        }
        return null;
    }

    /**
     * Encrypts the provided byte[]. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The byte[] to be encrypted
     * @return The encrypted ByteBuffer
     */
    public ByteBuffer encrypt(byte[] input){
        return encrypt(input, 0, input.length);
    }
    
    /**
     * Encrypts the provided ByteBuffer. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The byte[] to be encrypted
     * @return The encrypted ByteBuffer
     */
    public ByteBuffer encrypt(ByteBuffer input){
        if(input.hasArray())
            return encrypt(input.array(), input.arrayOffset(), input.capacity());
        else
            return encrypt(Fields.copyToArray(input));
    }

    // FIXME
    /* BitSet based operations commented out. If you need them, wait until we are using java 7,
     * or implement your own toByteArray(). Please don't steal it from OpenJDK because we are GPL2+
     * and link ASL2 code, therefore it is illegal for us to use GPL2-only code such as OpenJDK.
     * Please test very, VERY carefully, as it's essential that the representation not change when
     * we do switch to using java 7's BitSet.toByteArray(). The javadocs give a precise definition
     * so you can test it with unit tests. */
//    /**
//     * Encrypts the provided BitSet. If you are using a RijndaelECB
//     * alg then the length of input must equal the block size. 
//     * @param input The BitSet to encrypt
//     * @return The encrypted BitSet
//     */
//    public BitSet encrypt(BitSet input){
//        return BitSet.valueOf(encrypt(input.toByteArray()));
//    }

    /**
     * Decrypts the specified section of provided byte[] into an array which is returned as a
     * ByteBuffer. Does not modify the original array. If you are using a RijndaelECB alg then len 
     * must equal the block size. 
     * @param input The bytes to be decrypted
     * @param offset The position of input to start decrypting at
     * @param len The number of bytes after offset to decrypt
     * @return Returns ByteBuffer input with the specified section decrypted. The buffer will have
     * a backing array and its array offset will be 0.
     */
    public ByteBuffer decrypt(byte[] input, int offset, int len){
        try{
            if(type == CryptByteBufferType.RijndaelPCFB){
                // RijndaelPCFB will encrypt the original data. We don't want that, so copy.
                if(offset+len > input.length) throw new IllegalArgumentException();
                byte[] buf = Arrays.copyOfRange(input, offset, offset+len);
                decryptPCFB.blockDecipher(buf, 0, len);
                return ByteBuffer.wrap(buf);
            } 
            else if(type.cipherName.equals("RIJNDAEL")){
                byte[] result = new byte[len];
                if(offset+len > input.length) throw new IllegalArgumentException();
                blockCipher.decipher(Arrays.copyOfRange(input, offset, offset+len), result);
                return ByteBuffer.wrap(result);
            }
            else{
                return ByteBuffer.wrap(decryptCipher.doFinal(input, offset, len));
            }
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        } 
        return null;
    }

    /**
     * Decrypts the provided byte[]. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The byte[] to be decrypted
     * @return The decrypted ByteBuffer
     */
    public ByteBuffer decrypt(byte[] input){
        return decrypt(input, 0, input.length);
    }
    
    /**
     * Decrypts the provided ByteBuffer. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The ByteBuffer to be decrypted
     * @return The decrypted ByteBuffer
     */
    public ByteBuffer decrypt(ByteBuffer input){
        if(input.hasArray())
            return decrypt(input.array(), input.arrayOffset(), input.capacity());
        else
            return decrypt(Fields.copyToArray(input));
    }

    // FIXME
//    /**
//     * Decrypts the provided BitSet. If you are using a RijndaelECB
//     * alg then the length of input must equal the block size.
//     * @param input The BitSet to decrypt
//     * @return The decrypted BitSet
//     */
//    public BitSet decrypt(BitSet input){
//        return BitSet.valueOf(decrypt(input.toByteArray()));
//    }

    /**
     * Changes the current iv to the provided iv and initializes the cipher instances with
     * the new iv. Only works with algorithms that support IVs, not RijndaelPCFB.
     * @param iv The new iv to use as IvParameterSpec
     * @throws InvalidAlgorithmParameterException
     */
    public void setIV(IvParameterSpec iv) throws InvalidAlgorithmParameterException{
        if(!type.hasIV()){
            throw new UnsupportedTypeException(type);
        }
        this.iv = iv;
        try {
            encryptCipher.init(Cipher.ENCRYPT_MODE, this.key, this.iv);
            decryptCipher.init(Cipher.DECRYPT_MODE, this.key, this.iv);
        } catch (InvalidKeyException e) {
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        } 
    }

    /**
     * Generates a new IV to be used and initializes the cipher instances with
     * the new iv. Only works with algorithms that support IVs, not RijndaelPCFB.
     * @return The generated IV
     */
    public IvParameterSpec genIV(){
        if(!type.hasIV()){
            throw new UnsupportedTypeException(type);
        }
        this.iv = KeyGenUtils.genIV(type.ivSize);
        try {
            encryptCipher.init(Cipher.ENCRYPT_MODE, this.key, this.iv);
            decryptCipher.init(Cipher.DECRYPT_MODE, this.key, this.iv);
        } catch (GeneralSecurityException e) {
            Logger.error(CryptByteBuffer.class, "Internal error; please report:", e);
        } 
        return iv;
    }

    /**
     * Gets the IV being used. Only works with algorithms that support IVs.
     * @return Returns the iv as a IvParameterSpec
     */
    public IvParameterSpec getIV(){
        if(!type.hasIV()){
            throw new UnsupportedTypeException(type);
        }
        return iv;
    }
}