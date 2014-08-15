/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.crypto.generators.Poly1305KeyGenerator;

import freenet.support.Logger;

/**
 * The MessageAuthCode class will generate the Message Authentication Code of a given set
 * of bytes using a secret key. It can also verify 
 * @author unixninja92
 *
 */
public final class MessageAuthCode {
    public static final MACType preferredMAC = MACType.Poly1305AES;
    private final MACType type;
    private final Mac mac;
    private final SecretKey key;
    private IvParameterSpec iv;

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm and 
     * key. If that algorithms requires an IV it will generate one or use the passed in IV
     * @param type The MAC algorithm to use
     * @param key The key to use
     * @param genIv Does an IV need to be generated if type requires one
     * @param iv The iv to be used. Can be null if none provided or required. 
     * @throws InvalidKeyException
     */
    private MessageAuthCode(MACType type, SecretKey key, boolean genIv, IvParameterSpec iv) 
            throws InvalidKeyException{
        this.type = type;
        mac = type.get();
        this.key = key;
        try {
            if(type.ivlen != -1){;
            checkPoly1305Key(key.getEncoded());
            if(genIv){
                genIv();
            } else{
                setIv(iv);
            }
            mac.init(key, this.iv);
            }
            else{
                mac.init(key);
            }
        }catch (InvalidAlgorithmParameterException e) {
            Logger.error(MessageAuthCode.class, "Internal error; please report:", e);
        }
    }

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm and 
     * key. If that algorithms requires an IV it will generate one. 
     * @param type The MAC algorithm to use
     * @param cryptoKey The key to use
     * @throws InvalidKeyException
     */
    public MessageAuthCode(MACType type, SecretKey cryptoKey) throws InvalidKeyException {
        this(type, cryptoKey, true, null);
    }

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm and 
     * key which is converted from a byte[] to a SecretKey. If that algorithms requires 
     * an IV it will generate one. 
     * @param type The MAC algorithm to use
     * @param cryptoKey The key to use
     * @throws InvalidKeyException
     */
    public MessageAuthCode(MACType type, byte[] cryptoKey) throws InvalidKeyException {
        this(type, KeyGenUtils.getSecretKey(type.keyType, cryptoKey));	
    }
    
    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm and 
     * key which is converted from a ByteBuffer to a SecretKey. If that algorithms requires 
     * an IV it will generate one. 
     * @param type The MAC algorithm to use
     * @param cryptoKey The key to use
     * @throws InvalidKeyException
     */
    public MessageAuthCode(MACType type, ByteBuffer cryptoKey) throws InvalidKeyException {
        this(type, cryptoKey.array());  
    }

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm and 
     * will generate a key. If the algorithm requires an IV it will generate one. 
     * @param type The MAC algorithm to 
     * @throws InvalidKeyException
     */
    public MessageAuthCode(MACType type) throws InvalidKeyException{
        this(type, KeyGenUtils.genSecretKey(type.keyType));
    }

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm with 
     * the specified key and iv. The specified algorithm must require an iv.
     * @param key They key to be used
     * @param iv The iv to be used
     * @throws InvalidKeyException
     * @throws InvalidAlgorithmParameterException
     */
    public MessageAuthCode(MACType type, SecretKey key, IvParameterSpec iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, key, false, iv);
    }

    /**
     * Creates an instance of MessageAuthCode that will use the specified algorithm with 
     * the specified key and iv. The specified algorithm must require an iv.
     * @param key They key to be used as a byte[]
     * @param iv The iv to be used
     * @throws InvalidKeyException
     * @throws InvalidAlgorithmParameterException
     */
    public MessageAuthCode(MACType type, byte[] key, IvParameterSpec iv) 
            throws InvalidKeyException, InvalidAlgorithmParameterException{
        this(type, KeyGenUtils.getSecretKey(type.keyType, key), iv);
    }

    /**
     * Checks to make sure the provided key is a valid Poly1305 key
     * @param encodedKey Key to check
     */
    private final void checkPoly1305Key(byte[] encodedKey){
        if(type != MACType.Poly1305AES){
            throw new UnsupportedTypeException(type);
        }
        Poly1305KeyGenerator.checkKey(encodedKey);
    }

    /**
     * Adds the specified byte to the buffer of bytes to be used for MAC generation
     * @param input The byte to add
     */
    public final void addByte(byte input){
        mac.update(input);
    }

    /**
     * Adds the specified byte arrays to the buffer of bytes to be used for MAC generation
     * @param input The byte[]s to add
     */
    public final void addBytes(byte[]... input){
        for(byte[] b: input){
            if(b == null){
                throw new NullPointerException();
            }
            mac.update(b);
        }
    }

    /**
     * Adds the remaining bytes from a  ByteBuffer to the buffer of bytes 
     * to be used for MAC generation. The bytes read from the ByteBuffer will be from 
     * input.position() to input.remaining(). Upon return, the ByteBuffer's
     * .position() will be equal to .remaining() and .remaining() will 
     * stay unchanged. 
     * @param input The ByteBuffer to be added
     */
    public final void addBytes(ByteBuffer input){
        mac.update(input);
    }

    /**
     * Adds the specified portion of the byte[] passed in to the buffer 
     * of bytes to be used for MAC generation
     * @param input The byte to add
     * @param offset What byte to start at
     * @param len How many bytes after offset to add to buffer
     */
    public final void addBytes(byte[] input, int offset, int len){
        if(input == null){
            throw new NullPointerException();
        }
        mac.update(input, offset, len);
    }

    /**
     * Generates the MAC of all the bytes in the buffer added with the
     * addBytes methods. The buffer is then cleared after the MAC has been
     * generated.
     * @return The Message Authentication Code
     */
    public final ByteBuffer genMac(){
        return ByteBuffer.wrap(mac.doFinal());
    }
    

    /**
     * Generates the MAC of only the specified bytes. The buffer is cleared before 
     * processing the input to ensure that no extra data is included. Once the MAC
     * has been generated, the buffer is cleared again. 
     * @param input
     * @return The Message Authentication Code
     */
    public final ByteBuffer genMac(byte[]... input){
        mac.reset();
        addBytes(input);
        return genMac();
    }
    
    /**
     * Generates the MAC of only the specified bytes. The buffer is cleared before 
     * processing the input to ensure that no extra data is included. Once the MAC
     * has been generated, the buffer is cleared again. 
     * @param input
     * @return The Message Authentication Code
     */
    public final ByteBuffer genMac(ByteBuffer input){
        mac.reset();
        addBytes(input);
        return genMac();
    }

    /**
     * Verifies that the two MAC addresses passed are equivalent.
     * @param mac1 First MAC to be verified
     * @param mac2 Second MAC to be verified
     * @return Returns true if the MACs match, otherwise false.
     */
    public final static boolean verify(byte[] mac1, byte[] mac2){
        return MessageDigest.isEqual(mac1, mac2);
    }
    
    /**
     * Verifies that the two MAC addresses passed are equivalent.
     * @param mac1 First MAC to be verified
     * @param mac2 Second MAC to be verified
     * @return Returns true if the MACs match, otherwise false.
     */
    public final static boolean verify(ByteBuffer mac1, ByteBuffer mac2){
        return mac1.equals(mac2);
    }

    /**
     * Generates the MAC of the byte arrays provided and checks to see if that MAC
     * is the same as the one passed in. The buffer is cleared before processing the 
     * input to ensure that no extra data is included. Once the MAC has been 
     * generated, the buffer is cleared again. 
     * @param otherMac The MAC to check
     * @param data The data to check the MAC against
     * @return Returns true if it is a match, otherwise false.
     */
    public final boolean verifyData(byte[] otherMac, byte[]... data){
        return verify(genMac(data).array(), otherMac);
    }
    
    /**
     * Generates the MAC of the byte arrays provided and checks to see if that MAC
     * is the same as the one passed in. The buffer is cleared before processing the 
     * input to ensure that no extra data is included. Once the MAC has been 
     * generated, the buffer is cleared again. 
     * @param otherMac The MAC to check
     * @param data The data to check the MAC against
     * @return Returns true if it is a match, otherwise false.
     */
    public final boolean verifyData(ByteBuffer otherMac, ByteBuffer data){
        return verify(genMac(data), otherMac);
    }

    /**
     * Gets the key being used
     * @return Returns the key as a SecretKey
     */
    public final SecretKey getKey(){
        return key;
    }

    /**
     * Gets the IV being used. Only works with algorithms that support IVs.
     * @return Returns the iv as a IvParameterSpec
     */
    public final IvParameterSpec getIv() {
        if(type.ivlen == -1){
            throw new UnsupportedTypeException(type);
        }
        return iv;
    }

    /**
     * Changes the current iv to the provided iv. Only works with algorithms that support IVs.
     * @param iv The new iv to use as IvParameterSpec
     * @throws InvalidAlgorithmParameterException
     */
    public final void setIv(IvParameterSpec iv) throws InvalidAlgorithmParameterException{
        if(type.ivlen == -1){
            throw new UnsupportedTypeException(type);
        }
        this.iv = iv;
        try {
            mac.init(key, iv);
        } catch (InvalidKeyException e) {
            Logger.error(MessageAuthCode.class, "Internal error; please report:", e);
        }
    }

    /**
     * Generates a new IV to be used. Only works with algorithms that support IVs.
     * @return The generated IV
     */
    public final IvParameterSpec genIv() {
        if(type.ivlen == -1){
            throw new UnsupportedTypeException(type);
        }
        try {
            setIv(KeyGenUtils.genIV(type.ivlen));
        } catch (InvalidAlgorithmParameterException e) {
            Logger.error(MessageAuthCode.class, "Internal error; please report:", e);
        }
        return this.iv;
    }
}
