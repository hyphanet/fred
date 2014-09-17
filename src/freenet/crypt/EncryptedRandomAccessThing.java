/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import freenet.support.Fields;
import freenet.support.io.RandomAccessThing;
/**
 * EncryptedRandomAccessThing is a encrypted RandomAccessThing implementation using a 
 * SkippingStreamCipher. 
 * @author unixninja92
 * Suggested EncryptedRandomAccessThingType to use: ChaCha128
 */
public final class EncryptedRandomAccessThing implements RandomAccessThing { 
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final EncryptedRandomAccessThingType type;
    private final RandomAccessThing underlyingThing;
    
    private final SkippingStreamCipher cipherRead;
    private final SkippingStreamCipher cipherWrite;
    private final ParametersWithIV cipherParams;//includes key
    
    private final SecretKey headerMacKey;
    
    private volatile boolean isClosed = false;
    
    private SecretKey unencryptedBaseKey;
    
    private final SecretKey headerEncKey;
    private byte[] headerEncIV;
    private final int version; 
    
    private static final long END_MAGIC = 0x2c158a6c7772acd3L;
    private static final int VERSION_AND_MAGIC_LENGTH = 12;
    
    /**
     * Creates an instance of EncryptedRandomAccessThing wrapping underlyingThing. Keys for key 
     * encryption and MAC generation are derived from the MasterSecret. If this is a new ERAT then
     * keys are generated and the footer is written to the end of the underlying RAT. Otherwise the
     * footer is read from the underlying RAT. 
     * @param type The algorithms to be used for the ERAT
     * @param underlyingThing The underlying RAT that will be storing the data. Must be larger than
     * the footer size specified in type. 
     * @param masterKey The MasterSecret that will be used to derive various keys. 
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public EncryptedRandomAccessThing(EncryptedRandomAccessThingType type, 
            RandomAccessThing underlyingThing, MasterSecret masterKey) throws IOException, 
            GeneralSecurityException{
        this.type = type;
        this.underlyingThing = underlyingThing;
        this.cipherRead = this.type.get();
        this.cipherWrite = this.type.get();
        
        MasterSecret masterSecret = masterKey;
        
        this.headerEncKey = masterSecret.deriveKey(type.encryptKey);
        
        this.headerMacKey = masterSecret.deriveKey(type.macKey);
        
        
        if(underlyingThing.size() < type.footerLen){
            throw new IOException("Underlying RandomAccessThing is not long enough to include the "
                    + "footer.");
        }
        
        byte[] footer = new byte[VERSION_AND_MAGIC_LENGTH];
        int offset = 0;
        underlyingThing.pread(underlyingThing.size()-VERSION_AND_MAGIC_LENGTH, footer, offset, 
                VERSION_AND_MAGIC_LENGTH);
        
        int readVersion = ByteBuffer.wrap(footer, offset, 4).getInt();
        offset += 4;
        long magic = ByteBuffer.wrap(footer, offset, 8).getLong();

        if(END_MAGIC != magic && magic != 0){
        	throw new IOException("This is not an EncryptedRandomAccessThing!");
        }

        version = type.bitmask;
        if(magic == 0){
            this.headerEncIV = KeyGenUtils.genIV(type.encryptType.ivSize).getIV();
            this.unencryptedBaseKey = KeyGenUtils.genSecretKey(type.encryptKey);
        	writeFooter();
        }
        else{
        	if(readVersion != version){
        		throw new IOException("Version of the underlying RandomAccessThing is "
        				+ "incompatible with this ERATType");
        	}

        	if(!verifyFooter()){
        		throw new GeneralSecurityException("MAC is incorrect");
        	}
        }
        ParametersWithIV tempPram = null;
        try{
            KeyParameter cipherKey = new KeyParameter(KeyGenUtils.deriveSecretKey(unencryptedBaseKey, 
        			getClass(), kdfInput.underlyingKey.input, 
        			type.encryptKey).getEncoded());
            tempPram = new ParametersWithIV(cipherKey, 
        			KeyGenUtils.deriveIvParameterSpec(unencryptedBaseKey, getClass(), 
        					kdfInput.underlyingIV.input, type.encryptKey).getIV());
        } catch(InvalidKeyException e) {
            throw new IllegalStateException(e); // Must be a bug.
        }
        this.cipherParams = tempPram;
    	cipherRead.init(false, cipherParams);
    	cipherWrite.init(true, cipherParams);
    }

    @Override
    public long size() throws IOException {
        if(isClosed){
            throw new IOException("This RandomAccessThing has already been closed.");
        }
        return underlyingThing.size()-type.footerLen;
    }

    /**
     * Reads the specified section of the underlying RAT and decrypts it. Decryption is thread-safe. 
     */
    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
        if(isClosed){
            throw new IOException("This RandomAccessThing has already been closed. It can no longer"
                    + " be read from.");
        }

        if(fileOffset < 0) throw new IOException("Cannot read before zero");
        if(fileOffset+length > size()){
            throw new IOException("Cannot read after end: trying to read from "+fileOffset+" to "+
                    (fileOffset+length)+" on block length "+size());
        }
        
        byte[] cipherText = new byte[length];
        underlyingThing.pread(fileOffset, cipherText, 0, length);

        readLock.lock();
        try{
            cipherRead.seekTo(fileOffset);
            cipherRead.processBytes(cipherText, 0, length, buf, bufOffset);
        }finally{
            readLock.unlock();
        }
    }

    /**
     * Encrypts the given data and writes it to the underlying RAT. Encryption is thread-safe. 
     */
    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
        if(isClosed){
            throw new IOException("This RandomAccessThing has already been closed. It can no longer"
                    + " be written to.");
        }

        if(fileOffset < 0) throw new IOException("Cannot read before zero");
        if(fileOffset+length > size()){
            throw new IOException("Cannot write after end: trying to write from "+fileOffset+" to "+
                    (fileOffset+length)+" on block length "+size());
        }

        byte[] cipherText = new byte[length];

        writeLock.lock();
        try{
            cipherWrite.seekTo(fileOffset);
            cipherWrite.processBytes(buf, bufOffset, length, cipherText, 0);
        }finally{
            writeLock.unlock();
        }
        underlyingThing.pwrite(fileOffset, cipherText, 0, length);
    }
    
    @Override
    public void  close() {
        if(!isClosed){
            isClosed = true;
            underlyingThing.close();
        }
    }
    
    /**
     * Writes the footer to the end of the underlying RAT
     * @throws IOException
     * @throws GeneralSecurityException
     */
    private void writeFooter() throws IOException, GeneralSecurityException{
        if(isClosed){
            throw new IOException("This RandomAccessThing has already been closed. This should not"
                    + " happen.");
        }
        byte[] footer = new byte[type.footerLen];
        int offset = 0;
        
        int ivLen = headerEncIV.length;
        System.arraycopy(headerEncIV, 0, footer, offset, ivLen);
        offset += ivLen;

        byte[] encryptedKey = null;
        try {
            CryptByteBuffer crypt = new CryptByteBuffer(type.encryptType, headerEncKey, 
                    headerEncIV);
            encryptedKey = crypt.encryptCopy(unencryptedBaseKey.getEncoded());
        } catch (InvalidKeyException e) {
            throw new GeneralSecurityException("Something went wrong with key generation. please "
                    + "report", e.fillInStackTrace());
        } catch (InvalidAlgorithmParameterException e) {
            throw new GeneralSecurityException("Something went wrong with key generation. please "
                    + "report", e.fillInStackTrace());
        }
        System.arraycopy(encryptedKey, 0, footer, offset, encryptedKey.length);
        offset += encryptedKey.length;

        byte[] ver = ByteBuffer.allocate(4).putInt(version).array();
        try {
            MessageAuthCode mac = new MessageAuthCode(type.macType, headerMacKey);
            byte[] macResult = Fields.copyToArray(mac.genMac(headerEncIV, unencryptedBaseKey.getEncoded(), ver));
            System.arraycopy(macResult, 0, footer, offset, macResult.length);
            offset += macResult.length;
        } catch (InvalidKeyException e) {
            throw new GeneralSecurityException("Something went wrong with key generation. please "
                    + "report", e.fillInStackTrace());
        }
        
        System.arraycopy(ver, 0, footer, offset, ver.length);
        offset +=ver.length; 
        
        byte[] magic = ByteBuffer.allocate(8).putLong(END_MAGIC).array();
        System.arraycopy(magic, 0, footer, offset, magic.length);
        
        underlyingThing.pwrite(size(), footer, 0, footer.length);
    }
    
    /**
     * Reads the iv, the encrypted key and the MAC from the footer. Then decrypts they key and 
     * verifies the MAC. 
     * @return Returns true if the MAC is verified. Otherwise false. 
     * @throws IOException
     * @throws InvalidKeyException
     */
    private boolean verifyFooter() throws IOException, InvalidKeyException {
        if(isClosed){
            throw new IOException("This RandomAccessThing has already been closed. This should not"
                    + " happen.");
        }
        byte[] footer = new byte[type.footerLen-VERSION_AND_MAGIC_LENGTH];
        int offset = 0;
        underlyingThing.pread(size(), footer, offset, type.footerLen-VERSION_AND_MAGIC_LENGTH);
        
        headerEncIV = new byte[type.encryptType.ivSize];
        System.arraycopy(footer, offset, headerEncIV, 0, headerEncIV.length);
        offset += headerEncIV.length;
        
        int keySize = type.encryptKey.keySize >> 3;
        byte[] encryptedKey = new byte[keySize];
        System.arraycopy(footer, offset, encryptedKey, 0, keySize);
        offset += keySize;
        try {
            CryptByteBuffer crypt = new CryptByteBuffer(type.encryptType, headerEncKey, 
                    headerEncIV);
            unencryptedBaseKey = KeyGenUtils.getSecretKey(type.encryptKey, 
                    crypt.decryptCopy(encryptedKey));
        } catch (InvalidKeyException e) {
            throw new IOException("Error reading encryption keys from header.");
        } catch (InvalidAlgorithmParameterException e) {
            throw new IOException("Error reading encryption keys from header.");
        }
        
        byte[] mac = new byte[type.macLen];
        System.arraycopy(footer, offset, mac, 0, type.macLen);
        
        byte[] ver = ByteBuffer.allocate(4).putInt(version).array();
        MessageAuthCode authcode = new MessageAuthCode(type.macType, headerMacKey);
        return authcode.verifyData(mac, headerEncIV, unencryptedBaseKey.getEncoded(), ver);
    }
    
    /**
     *  The Strings used to derive keys and ivs from the unencryptedBaseKey. 
     */
    private enum kdfInput {
        underlyingKey(),/** For deriving the key that will be used to encrypt the underlying RAT*/
        underlyingIV();/** For deriving the iv that will be used to encrypt the underlying RAT*/
        
        public final String input;
        
        private kdfInput(){
            this.input = name();
        }
        
    }

}
