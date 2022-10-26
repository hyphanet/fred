/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;

import freenet.crypt.ciphers.Rijndael;
import freenet.support.Fields;

/**
 * CryptByteBuffer will encrypt and decrypt both byte[]s and BitSets with a specified
 * algorithm, key, and also an iv if the algorithm requires one. Note that the input and the 
 * output are the same length, i.e. these are either stream ciphers or non-padded block ciphers
 * (where it must be called with whole blocks). For a stream cipher, once a CryptByteBuffer is 
 * initialised, processed bytes will be treated as a single stream, i.e. repeatedly encrypting the
 * same data will give different results.
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
            throw new Error(e); // Should be impossible as we bundle BC
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e); // Should be impossible as we bundle BC
        } catch (NoSuchPaddingException e) {
            throw new Error(e); // Should be impossible as we don't use padded modes
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
    
    /** Encrypt the specified section of the provided byte[] to the output byte[], without 
     * modifying the input. */
    public void encrypt(byte[] input, int offset, int len, byte[] output, int outputOffset) {
        if(offset+len > input.length) throw new IllegalArgumentException();
        if(input == output && offset != outputOffset) {
            // FIXME only copy if it actually overlaps...
            byte[] temp = Arrays.copyOfRange(input, offset, offset+len);
            encrypt(temp, 0, temp.length);
            System.arraycopy(temp, 0, output, outputOffset, len);
            return;
        }
        if(type == CryptByteBufferType.RijndaelPCFB){
            System.arraycopy(input, offset, output, outputOffset, len);
            encryptPCFB.blockEncipher(output, outputOffset, len);
        } else if(type.cipherName.equals("RIJNDAEL")){
            if(offset == 0 && len == input.length && outputOffset == 0 && len == output.length)
                blockCipher.encipher(input, output);
            else {
                byte[] result = new byte[len];
                blockCipher.encipher(Arrays.copyOfRange(input, offset, offset+len), result);
                System.arraycopy(result, 0, output, outputOffset, len);
            }
        } else {
            try {
                int copied = encryptCipher.update(input, offset, len, output, outputOffset);
                if(copied != len) throw new IllegalStateException("Not a stream cipher???");
            } catch (ShortBufferException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }
    
    /** Encrypt the specified section of the provided byte[] in-place */
    public void encrypt(byte[] input, int offset, int len) {
        encrypt(input, offset, len, input, offset);
    }
    
    /**
     * Encrypts the specified section of provided byte[] into a new array returned as a ByteBuffer.
     * Does not modify the original array. If you are using a RijndaelECB alg then len must equal 
     * the block size. 
     * @param input The bytes to be encrypted. Contents will not be modified.
     * @param offset The position of input to start encrypting at
     * @param len The number of bytes after offset to encrypt
     * @return Returns a new array containing the ciphertext encoding the specified range.
     */
    public byte[] encryptCopy(byte[] input, int offset, int len){
        byte[] output = Arrays.copyOfRange(input, offset, offset+len);
        encrypt(input, offset, len, output, 0);
        return output;
    }

    /**
     * Encrypts the provided byte[]. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The byte[] to be encrypted
     * @return The encrypted data. The original data will be unchanged.
     */
    public byte[] encryptCopy(byte[] input){
        return encryptCopy(input, 0, input.length);
    }
    
    /**
     * Encrypts the provided ByteBuffer, returning a new ByteBuffer. Only reads the bytes that are
     * actually readable, i.e. from position to limit, so equivalent to get()ing into a buffer, 
     * encrypting that and returning. If you are using a RijndaelECB alg then the length of input 
     * must equal the block size. 
     * @param input The byte[] to be encrypted
     * @return A new ByteBuffer containing the ciphertext. It will have a backing array and its 
     * arrayOffset() will be 0, its position will be 0 and its capacity will be the length of the
     * input data.
     */
    public ByteBuffer encryptCopy(ByteBuffer input){
        if(input.hasArray())
            return ByteBuffer.wrap(encryptCopy(input.array(), input.arrayOffset() + input.position(),
                    input.remaining()));
        else {
            return ByteBuffer.wrap(encryptCopy(Fields.copyToArray(input)));
        }
    }
    
    /** Get bytes from one ByteBuffer and encrypt them and put them into the other ByteBuffer. */
    public void encrypt(ByteBuffer input, ByteBuffer output) {
        if(input.hasArray() && output.hasArray()) {
            int moved = Math.min(input.remaining(), output.remaining());
            encrypt(input.array(), input.arrayOffset()+input.position(), moved,
                    output.array(), output.arrayOffset()+output.position());
            input.position(input.position()+moved);
            output.position(output.position()+moved);
        } else if(!(type == CryptByteBufferType.RijndaelPCFB || type.cipherName.equals("RIJNDAEL"))) {
            // Use ByteBuffer to ByteBuffer operations.
            try {
                int copy = Math.min(input.remaining(), output.remaining());
                int copied = encryptCipher.update(input, output);
                if(copied != copy) throw new IllegalStateException("Not a stream cipher???");
            } catch (ShortBufferException e) {
                throw new Error("Impossible: "+e, e);
            }
        } else {
            // FIXME use a smaller temporary buffer
            int moved = Math.min(input.remaining(), output.remaining());
            byte[] buf = new byte[moved];
            input.get(buf);
            encrypt(buf, 0, buf.length);
            output.put(buf);
        }
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
    
    /** Decrypt the specified section of the provided byte[] to the output byte[], without 
     * modifying the input. */
    public void decrypt(byte[] input, int offset, int len, byte[] output, int outputOffset) {
        if(offset+len > input.length) throw new IllegalArgumentException();
        if(input == output && offset != outputOffset) {
            // FIXME only copy if it actually overlaps...
            byte[] temp = Arrays.copyOfRange(input, offset, offset+len);
            decrypt(temp, 0, temp.length);
            System.arraycopy(temp, 0, output, outputOffset, len);
            return;
        }
        if(type == CryptByteBufferType.RijndaelPCFB){
            System.arraycopy(input, offset, output, outputOffset, len);
            decryptPCFB.blockDecipher(output, outputOffset, len);
        } else if(type.cipherName.equals("RIJNDAEL")){
            if(offset == 0 && len == input.length && outputOffset == 0 && len == output.length)
                blockCipher.decipher(input, output);
            else {
                byte[] result = new byte[len];
                blockCipher.decipher(Arrays.copyOfRange(input, offset, offset+len), result);
                System.arraycopy(result, 0, output, outputOffset, len);
            }
        } else {
            try {
                int copied = decryptCipher.update(input, offset, len, output, outputOffset);
                if(copied != len) throw new IllegalStateException("Not a stream cipher???");
            } catch (ShortBufferException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }
    
    /** Decrypt the specified section of the provided byte[] in-place */
    public void decrypt(byte[] input, int offset, int len) {
        decrypt(input, offset, len, input, offset);
    }
    
    /**
     * Decrypts the specified section of provided byte[] into an array which is returned as a
     * ByteBuffer. Does not modify the original array. If you are using a RijndaelECB alg then len 
     * must equal the block size. 
     * @param input The bytes to be decrypted. Contents will not be modified.
     * @param offset The position of input to start decrypting at
     * @param len The number of bytes after offset to decrypt
     * @return Returns the decrypted plaintext, a newly allocated byte array of the same length as 
     * the input data. 
     */
    public byte[] decryptCopy(byte[] input, int offset, int len){
        byte[] output = Arrays.copyOfRange(input, offset, offset+len);
        decrypt(input, offset, len, output, 0);
        return output;
    }

    /**
     * Decrypts the provided byte[]. If you are using a RijndaelECB
     * alg then the length of input must equal the block size. 
     * @param input The byte[] to be decrypted
     * @return The decrypted plaintext bytes.
     */
    public byte[] decryptCopy(byte[] input){
        return decryptCopy(input, 0, input.length);
    }
    
    /**
     * Decrypts the provided ByteBuffer, returning a new ByteBuffer. Only reads the bytes that are
     * actually readable, i.e. from position to limit, so equivalent to get()ing into a buffer, 
     * decrypting that and returning. If you are using a RijndaelECB alg then the length of input 
     * must equal the block size. 
     * @param input The buffer to be decrypted
     * @return A new ByteBuffer containing the plaintext. It will have a backing array and its 
     * arrayOffset() will be 0, its position will be 0 and its capacity will be the length of the
     * input data.
     */
    public ByteBuffer decryptCopy(ByteBuffer input){
        if(input.hasArray())
            return ByteBuffer.wrap(decryptCopy(input.array(), input.arrayOffset() + input.position(), 
                    input.remaining()));
        else
            return ByteBuffer.wrap(decryptCopy(Fields.copyToArray(input)));
    }
    
    /** Get bytes from one ByteBuffer and encrypt them and put them into the other ByteBuffer. */
    public void decrypt(ByteBuffer input, ByteBuffer output) {
        if(input.hasArray() && output.hasArray()) {
            int moved = Math.min(input.remaining(), output.remaining());
            decrypt(input.array(), input.arrayOffset()+input.position(), moved,
                    output.array(), output.arrayOffset()+output.position());
            input.position(input.position()+moved);
            output.position(output.position()+moved);
        } else if(!(type == CryptByteBufferType.RijndaelPCFB || type.cipherName.equals("RIJNDAEL"))) {
            // Use ByteBuffer to ByteBuffer operations.
            try {
                int copy = Math.min(input.remaining(), output.remaining());
                int copied = decryptCipher.update(input, output);
                if(copied != copy) throw new IllegalStateException("Not a stream cipher???");
            } catch (ShortBufferException e) {
                throw new Error("Impossible: "+e, e);
            }
        } else {
            // FIXME use a smaller temporary buffer
            int moved = Math.min(input.remaining(), output.remaining());
            byte[] buf = new byte[moved];
            input.get(buf);
            decrypt(buf, 0, buf.length);
            output.put(buf);
        }
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
            throw new IllegalArgumentException(e);
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
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException(e); // Definitely a bug ...
        } catch (InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e); // Definitely a bug ...
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