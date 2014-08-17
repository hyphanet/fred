/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.BitSet;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.Logger;

/**
 * CryptByteBuffer will encrypt and decrypt both byte[]s and BitSets with a specified
 * algorithm, key, and also an iv if the algorithm requires one. 
 * @author unixninja92
 */
@SuppressWarnings("deprecation") // Suppresses warnings about RijndaelPCFB being deprecated
public final class CryptByteBuffer implements Serializable{
    private static final long serialVersionUID = 6143338995971755362L;
    public static final CryptByteBufferType preferredCryptBitAlg = CryptByteBufferType.ChaCha128;
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
            if(type.cipherName == "RIJNDAEL"){
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
        }catch (UnsupportedCipherException e) {
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
        this(type, key.array());
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
        this(type, key, iv.array(), 0);
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
        this(type, key.array(), iv.array(), 0);
    }

    /**
     * Encrypts the specified section of provided byte[]. If you are using a RijndaelECB
     * alg then len must equal the block size. 
     * @param input The bytes to be encrypted
     * @param offset The position of input to start encrypting at
     * @param len The number of bytes after offset to encrypt
     * @return Returns ByteBuffer input with the specified section encrypted
     */
    public ByteBuffer encrypt(byte[] input, int offset, int len){
        try{
            if(type == CryptByteBufferType.RijndaelPCFB){
                return ByteBuffer.wrap(encryptPCFB.blockEncipher(input, offset, len));
            } 
            else if(type.cipherName == "RIJNDAEL"){
                byte[] result = new byte[len];
                blockCipher.encipher(extractSmallerArray(input, offset, len), result);
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
        return encrypt(input.array());
    }

    /**
     * Encrypts the provided BitSet. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The BitSet to encrypt
     * @return The encrypted BitSet
     */
    public BitSet encrypt(BitSet input){
        return BitSet.valueOf(encrypt(input.toByteArray()));
    }

    /**
     * Decrypts the specified section of provided byte[]. If you are using a RijndaelECB
     * alg then len must equal the block size. 
     * @param input The bytes to be decrypted
     * @param offset The position of input to start decrypting at
     * @param len The number of bytes after offset to decrypt
     * @return Returns ByteBuffer input with the specified section decrypted
     */
    public ByteBuffer decrypt(byte[] input, int offset, int len){
        try{
            if(type == CryptByteBufferType.RijndaelPCFB){
                return ByteBuffer.wrap(decryptPCFB.blockDecipher(input, offset, len));
            } 
            else if(type.cipherName == "RIJNDAEL"){
                byte[] result = new byte[len];
                blockCipher.decipher(extractSmallerArray(input, offset, len), result);
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
        return decrypt(input.array());
    }

    /**
     * Decrypts the provided BitSet. If you are using a RijndaelECB
     * alg then the length of input must equal the block size.
     * @param input The BitSet to decrypt
     * @return The decrypted BitSet
     */
    public BitSet decrypt(BitSet input){
        return BitSet.valueOf(decrypt(input.toByteArray()));
    }

    /**
     * Changes the current iv to the provided iv and initializes the cipher instances with
     * the new iv. Only works with algorithms that support IVs.
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
     * the new iv. Only works with algorithms that support IVs.
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

    /**
     * Extracts a subset of a byte array
     * @param input The byte[] to extract from
     * @param offset Where to start extracting
     * @param len How many bytes to extract after offset
     * @return The extracted subset
     */
    private byte[] extractSmallerArray(byte[] input, int offset, int len){
        if(input.length == len && offset == 0){
            return input;
        }
        else{
            return ByteBuffer.wrap(input, offset, len).array();
        }
    }
}