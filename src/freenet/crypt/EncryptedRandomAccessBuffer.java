/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.crypt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import freenet.client.async.ClientContext;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.api.LockableRandomAccessBuffer;
import freenet.support.io.BucketTools;
import freenet.support.io.FilenameGenerator;
import freenet.support.io.PersistentFileTracker;
import freenet.support.io.ResumeFailedException;
import freenet.support.io.StorageFormatException;
/**
 * EncryptedRandomAccessBuffer is a encrypted RandomAccessBuffer implementation using a 
 * SkippingStreamCipher. 
 * @author unixninja92
 * Suggested EncryptedRandomAccessBufferType to use: ChaCha128
 */
public final class EncryptedRandomAccessBuffer implements LockableRandomAccessBuffer, Serializable { 
    private static final long serialVersionUID = 1L;
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final EncryptedRandomAccessBufferType type;
    private final LockableRandomAccessBuffer underlyingBuffer;
    
    private transient SkippingStreamCipher cipherRead;
    private transient SkippingStreamCipher cipherWrite;
    private transient ParametersWithIV cipherParams;//includes key
    
    private transient SecretKey headerMacKey;
    
    private transient volatile boolean isClosed = false;
    
    private transient SecretKey unencryptedBaseKey;
    
    private transient SecretKey headerEncKey;
    private transient byte[] headerEncIV;
    private int version; 
    
    private static final long END_MAGIC = 0x2c158a6c7772acd3L;
    private static final int VERSION_AND_MAGIC_LENGTH = 12;
    
    /**
     * Creates an instance of EncryptedRandomAccessBuffer wrapping underlyingBuffer. Keys for key 
     * encryption and MAC generation are derived from the MasterSecret. If this is a new ERAT then
     * keys are generated and the footer is written to the end of the underlying RAT. Otherwise the
     * footer is read from the underlying RAT. 
     * @param type The algorithms to be used for the ERAT
     * @param underlyingBuffer The underlying RAT that will be storing the data. Must be larger than
     * the footer size specified in type. 
     * @param masterKey The MasterSecret that will be used to derive various keys. 
     * @param newFile If true, treat it as a new file, and writer a header. If false, the ERAT must 
     * already have been initialised.
     * @throws IOException
     * @throws GeneralSecurityException
     */
    public EncryptedRandomAccessBuffer(EncryptedRandomAccessBufferType type, 
            LockableRandomAccessBuffer underlying, MasterSecret masterKey, boolean newFile) throws IOException, 
            GeneralSecurityException{
        this.type = type;
        this.underlyingBuffer = underlying;
        
        setup(masterKey, newFile);
    }
    
    private void setup(MasterSecret masterKey, boolean newFile) throws IOException, GeneralSecurityException {
        this.cipherRead = this.type.get();
        this.cipherWrite = this.type.get();
        
        MasterSecret masterSecret = masterKey;
        
        this.headerEncKey = masterSecret.deriveKey(type.encryptKey);
        
        this.headerMacKey = masterSecret.deriveKey(type.macKey);
        
        
        if(underlyingBuffer.size() < type.headerLen){
            throw new IOException("Underlying RandomAccessBuffer is not long enough to include the "
                    + "footer.");
        }
        
        byte[] header = new byte[VERSION_AND_MAGIC_LENGTH];
        int offset = 0;
        underlyingBuffer.pread(type.headerLen-VERSION_AND_MAGIC_LENGTH, header, offset, 
                VERSION_AND_MAGIC_LENGTH);
        
        int readVersion = ByteBuffer.wrap(header, offset, 4).getInt();
        offset += 4;
        long magic = ByteBuffer.wrap(header, offset, 8).getLong();

        if(!newFile && END_MAGIC != magic) {
        	throw new IOException("This is not an EncryptedRandomAccessBuffer!");
        }

        version = type.bitmask;
        if(newFile) {
            this.headerEncIV = KeyGenUtils.genIV(type.encryptType.ivSize).getIV();
            this.unencryptedBaseKey = KeyGenUtils.genSecretKey(type.encryptKey);
        	writeHeader();
        } else {
        	if(readVersion != version){
        		throw new IOException("Version of the underlying RandomAccessBuffer is "
        				+ "incompatible with this ERATType");
        	}

        	if(!verifyHeader()){
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
    public long size() {
        return underlyingBuffer.size()-type.headerLen;
    }

    /**
     * Reads the specified section of the underlying RAT and decrypts it. Decryption is thread-safe. 
     */
    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
        if(isClosed){
            throw new IOException("This RandomAccessBuffer has already been closed. It can no longer"
                    + " be read from.");
        }

        if(fileOffset < 0) throw new IllegalArgumentException("Cannot read before zero");
        if(fileOffset+length > size()){
            throw new IOException("Cannot read after end: trying to read from "+fileOffset+" to "+
                    (fileOffset+length)+" on block length "+size());
        }
        
        byte[] cipherText = new byte[length];
        underlyingBuffer.pread(fileOffset+type.headerLen, cipherText, 0, length);

        readLock.lock();
        try{
            //cipherRead.seekTo(fileOffset);
            // seekTo() does reset() and then skip(). So it always skips from 0. 
            // This is ridiculously slow for big tempfiles.
            // FIXME REVIEW CRYPTO: Is this safe? It should be, we're using the published skip() API...
            long position = cipherRead.getPosition();
            long delta = fileOffset - position;
            cipherRead.skip(delta);
            assert(cipherRead.getPosition() == fileOffset);
            cipherRead.processBytes(cipherText, 0, length, buf, bufOffset);
            assert(cipherRead.getPosition() == fileOffset+length);
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
            throw new IOException("This RandomAccessBuffer has already been closed. It can no longer"
                    + " be written to.");
        }

        if(fileOffset < 0) throw new IllegalArgumentException("Cannot read before zero");
        if(fileOffset+length > size()){
            throw new IOException("Cannot write after end: trying to write from "+fileOffset+" to "+
                    (fileOffset+length)+" on block length "+size());
        }

        byte[] cipherText = new byte[length];

        writeLock.lock();
        try{
            //cipherWrite.seekTo(fileOffset)
            // seekTo() does reset() and then skip(). So it always skips from 0. 
            // This is ridiculously slow for big tempfiles.
            // FIXME REVIEW CRYPTO: Is this safe? It should be, we're using the published skip() API...
            long position = cipherWrite.getPosition();
            long delta = fileOffset - position;
            cipherWrite.skip(delta);
            assert(cipherWrite.getPosition() == fileOffset);
            cipherWrite.processBytes(buf, bufOffset, length, cipherText, 0);
            assert(cipherWrite.getPosition() == fileOffset+length);
        }finally{
            writeLock.unlock();
        }
        underlyingBuffer.pwrite(fileOffset+type.headerLen, cipherText, 0, length);
    }
    
    @Override
    public void  close() {
        if(!isClosed){
            isClosed = true;
            underlyingBuffer.close();
        }
    }
    
    @Override
    public void free() {
        close();
        underlyingBuffer.free();
    }

    /**
     * Writes the footer to the end of the underlying RAT
     * @throws IOException
     * @throws GeneralSecurityException
     */
    private void writeHeader() throws IOException, GeneralSecurityException{
        if(isClosed){
            throw new IOException("This RandomAccessBuffer has already been closed. This should not"
                    + " happen.");
        }
        byte[] header = new byte[type.headerLen];
        int offset = 0;
        
        int ivLen = headerEncIV.length;
        System.arraycopy(headerEncIV, 0, header, offset, ivLen);
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
        System.arraycopy(encryptedKey, 0, header, offset, encryptedKey.length);
        offset += encryptedKey.length;

        byte[] ver = ByteBuffer.allocate(4).putInt(version).array();
        try {
            MessageAuthCode mac = new MessageAuthCode(type.macType, headerMacKey);
            byte[] macResult = Fields.copyToArray(mac.genMac(headerEncIV, unencryptedBaseKey.getEncoded(), ver));
            System.arraycopy(macResult, 0, header, offset, macResult.length);
            offset += macResult.length;
        } catch (InvalidKeyException e) {
            throw new GeneralSecurityException("Something went wrong with key generation. please "
                    + "report", e.fillInStackTrace());
        }
        
        System.arraycopy(ver, 0, header, offset, ver.length);
        offset +=ver.length; 
        
        byte[] magic = ByteBuffer.allocate(8).putLong(END_MAGIC).array();
        System.arraycopy(magic, 0, header, offset, magic.length);
        
        underlyingBuffer.pwrite(0, header, 0, header.length);
    }
    
    /**
     * Reads the iv, the encrypted key and the MAC from the footer. Then decrypts they key and 
     * verifies the MAC. 
     * @return Returns true if the MAC is verified. Otherwise false. 
     * @throws IOException
     * @throws InvalidKeyException
     */
    private boolean verifyHeader() throws IOException, InvalidKeyException {
        if(isClosed){
            throw new IOException("This RandomAccessBuffer has already been closed. This should not"
                    + " happen.");
        }
        byte[] footer = new byte[type.headerLen-VERSION_AND_MAGIC_LENGTH];
        int offset = 0;
        underlyingBuffer.pread(0, footer, offset, type.headerLen-VERSION_AND_MAGIC_LENGTH);
        
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
    enum kdfInput {
        underlyingKey(),/** For deriving the key that will be used to encrypt the underlying RAT*/
        underlyingIV();/** For deriving the iv that will be used to encrypt the underlying RAT*/
        
        public final String input;
        
        private kdfInput(){
            this.input = name();
        }
        
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        return underlyingBuffer.lockOpen();
    }
    
    public static final int MAGIC = 0x39ea94c2;

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        underlyingBuffer.onResume(context);
        try {
            setup(context.getPersistentMasterSecret(), false);
        } catch (IOException e) {
            Logger.error(this, "Disk I/O error resuming: "+e, e);
            throw new ResumeFailedException(e);
        } catch (GeneralSecurityException e) {
            Logger.error(this, "Impossible security error resuming - maybe we lost a codec?: "+e, e);
            throw new ResumeFailedException(e);
        }
    }

    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(type.bitmask);
        underlyingBuffer.storeTo(dos);
    }

    public static LockableRandomAccessBuffer create(DataInputStream dis, FilenameGenerator fg, PersistentFileTracker persistentFileTracker, MasterSecret masterKey) 
    throws IOException, StorageFormatException, ResumeFailedException {
        EncryptedRandomAccessBufferType type = EncryptedRandomAccessBufferType.getByBitmask(dis.readInt());
        if(type == null)
            throw new StorageFormatException("Unknown EncryptedRandomAccessBufferType");
        LockableRandomAccessBuffer underlying = BucketTools.restoreRAFFrom(dis, fg, persistentFileTracker, masterKey);
        try {
            return new EncryptedRandomAccessBuffer(type, underlying, masterKey, false);
        } catch (GeneralSecurityException e) {
            Logger.error(EncryptedRandomAccessBuffer.class, "Crypto error resuming: "+e, e);
            throw new ResumeFailedException(e);
        }
    }
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        result = prime * result + ((underlyingBuffer == null) ? 0 : underlyingBuffer.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        EncryptedRandomAccessBuffer other = (EncryptedRandomAccessBuffer) obj;
        if (type != other.type) {
            return false;
        }
        return underlyingBuffer.equals(other.underlyingBuffer);
    }
    
}
