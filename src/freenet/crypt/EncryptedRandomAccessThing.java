package freenet.crypt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import freenet.support.Logger;
import freenet.support.io.RandomAccessThing;
/**
 * REQUIRES BC 151 OR NEWER!!!!!! 
 * @author unixninja92
 *
 */
public final class EncryptedRandomAccessThing implements RandomAccessThing { 
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final EncryptedRandomAccessThingType type;
    private final RandomAccessThing underlyingThing;
    
    private SkippingStreamCipher cipherRead;
    private SkippingStreamCipher cipherWrite;
    private KeyParameter cipherKey;
    private ParametersWithIV cipherParams;//includes key
    
    private SecretKey headerMacKey;
    private IvParameterSpec headerMacIV;
    
    private boolean isClosed = false;
    
    private SecretKey unencryptedBaseKey;
    
    private MasterSecret masterSecret;
    private SecretKey headerEncKey;
    private byte[] headerEncIV;
    private int version; 
    
    private static final long END_MAGIC = 0x2c158a6c7772acd3L;
    
    //writes
    public EncryptedRandomAccessThing(EncryptedRandomAccessThingType type, 
            RandomAccessThing underlyingThing, MasterSecret masterKey) throws IOException{
        this.type = type;
        this.underlyingThing = underlyingThing;
        this.cipherRead = this.type.get();
        this.cipherWrite = this.type.get();
        
        this.masterSecret = masterKey;
        
        this.headerEncKey = this.masterSecret.deriveKey(type.encryptKey);
        this.headerEncIV = KeyGenUtils.genIV(type.encryptType.ivSize).getIV();
        
        this.headerMacKey = this.masterSecret.deriveKey(type.macKey);
        this.headerMacIV = this.masterSecret.deriveIv(type.macType.ivlen);
        
        this.unencryptedBaseKey = KeyGenUtils.genSecretKey(KeyType.HMACSHA512);
        
        if(underlyingThing.size() < type.footerLen){
            throw new IOException("Underlying RandomAccessThing is not long enough to include the "
                    + "footer.");
        }
        
        int len = 12;
        byte[] footer = new byte[len];
        int offset = 0;
        pread(size()-len, footer, offset, len);
        
        int readVersion = ByteBuffer.wrap(footer, offset, 4).getInt();
        offset += 4;
        long magic = ByteBuffer.wrap(footer, offset, 8).getLong();

        if(END_MAGIC != magic && magic == 0){
        	throw new IOException();
        }

        version = type.bitmask;
        if(magic == 0){
        	writeFooter();
        }
        else{
        	if(readVersion != version){
        		throw new IOException("Version of the underlying RandomAccessThing is "
        				+ "incompatible with this ERATType");
        	}

        	if(readFooter()){
        		throw new IOException("Macs is incorrect");
        	}
        }

        try{
        	this.cipherKey = new KeyParameter(KeyGenUtils.deriveSecretKey(unencryptedBaseKey, 
        			(Class<?>)this.getClass(), kdfInput.underlyingKey.input, 
        			type.encryptKey).getEncoded());
        	this.cipherParams = new ParametersWithIV(cipherKey, 
        			KeyGenUtils.deriveIvParameterSpec(unencryptedBaseKey, this.getClass(), 
        					kdfInput.underlyingIV.input, type.skippingCipherIVLen).getIV());
        } catch(InvalidKeyException e) {
            Logger.error(EncryptedRandomAccessThing.class, "Internal error; please report:", e);
        }

    	cipherRead.init(false, cipherParams);
    	cipherWrite.init(true, cipherParams);
    }

    @Override
    public long size() throws IOException {
        return underlyingThing.size() + type.footerLen;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {

        byte[] cipherText = new byte[length];
        underlyingThing.pread(fileOffset, cipherText, 0, length);

        readLock.lock();
        try{
            cipherRead.seekTo(fileOffset);
            cipherRead.processBytes(buf, 0, length, buf, bufOffset);
        }finally{
            readLock.unlock();
        }
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length)
            throws IOException {
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
    public void close() {
        isClosed = true;
        cipherRead = null;
        cipherWrite = null;
        cipherParams = null;
        underlyingThing.close();
    }
    
    private void writeFooter() throws IOException{
        byte[] footer = new byte[type.footerLen];
        int offset = 0;
        
        int ivLen = headerEncIV.length;
        System.arraycopy(headerEncIV, 0, footer, offset, ivLen);
        offset += ivLen;
        
        byte[] encryptedKey = null;
        try {
            CryptBitSet crypt = new CryptBitSet(type.encryptType, headerEncKey, 
                    headerEncIV);
            encryptedKey = crypt.encrypt(unencryptedBaseKey.getEncoded()).array();
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Logger.error(EncryptedRandomAccessThing.class, "Internal error; please report:", e);
        }
        System.arraycopy(encryptedKey, 0, footer, offset, encryptedKey.length);
        offset += encryptedKey.length;

        byte[] ver = ByteBuffer.allocate(4).putInt(version).array();
        try {
            MessageAuthCode mac = new MessageAuthCode(type.macType, headerMacKey, headerMacIV);
            byte[] macResult = mac.genMac(headerEncIV, unencryptedBaseKey.getEncoded(), ver).array();
            System.arraycopy(macResult, 0, footer, offset, macResult.length);
            offset += macResult.length;
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Logger.error(EncryptedRandomAccessThing.class, "Internal error; please report:", e);
        }
        
        System.arraycopy(ver, 0, footer, offset, ver.length);
        offset +=ver.length; 
        
        byte[] magic = ByteBuffer.allocate(8).putLong(END_MAGIC).array();
        System.arraycopy(magic, 0, footer, offset, magic.length);
        
        pwrite(size()-type.footerLen, footer, 0, type.footerLen);
    }
    
    private boolean readFooter() throws IOException {
        byte[] footer = new byte[type.footerLen-12];
        int offset = 0;
        pread(size()-type.footerLen, footer, offset, type.footerLen-12);
        
        headerEncIV = new byte[type.encryptType.ivSize];
        System.arraycopy(footer, offset, headerEncIV, 0, headerEncIV.length);
        offset += headerEncIV.length;
        
        int keySize = type.encryptKey.keySize >> 3;
        byte[] encryptedKey = new byte[keySize];
        System.arraycopy(footer, offset, encryptedKey, 0, keySize);
        offset += keySize;
        try {
            CryptBitSet crypt = new CryptBitSet(type.encryptType, headerEncKey, 
                    headerEncIV);
            unencryptedBaseKey = KeyGenUtils.getSecretKey(KeyType.HMACSHA512, 
                    crypt.decrypt(unencryptedBaseKey.getEncoded()));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IOException();
        }
        
        byte[] mac = new byte[type.macLen];
        System.arraycopy(footer, offset, mac, 0, type.macLen);
        
        byte[] ver = ByteBuffer.allocate(4).putInt(version).array();
        try{
            MessageAuthCode authcode = new MessageAuthCode(type.macType, headerMacKey, headerMacIV);
            return authcode.verifyData(mac, headerEncIV, unencryptedBaseKey.getEncoded(), ver);
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IOException();
        }
    }
    
    private enum kdfInput {
        baseIV(),
        underlyingKey(),
        underlyingIV();
        
        public final String input;
        
        private kdfInput(){
            this.input = this.getClass().getName()+name();
        }
        
    }

}
