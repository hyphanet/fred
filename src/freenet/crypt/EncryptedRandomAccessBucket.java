package freenet.crypt;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Arrays;

import javax.crypto.SecretKey;

import org.bouncycastle.crypto.SkippingStreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import freenet.crypt.EncryptedRandomAccessThing.kdfInput;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import freenet.support.io.LockableRandomAccessThing;
import freenet.support.io.NullInputStream;

/** A Bucket encrypted using the same format as an EncryptedRandomAccessThing, which can therefore
 * be converted easily when needed.
 * @author toad
 */
public class EncryptedRandomAccessBucket implements RandomAccessBucket {
    
    private static final long serialVersionUID = 1L;
    
    private final EncryptedRandomAccessThingType type;
    private final RandomAccessBucket underlying;
    
    private transient ParametersWithIV cipherParams;//includes key
    
    private transient SecretKey headerMacKey;
    
    private transient volatile boolean isFreed = false;
    
    private transient SecretKey unencryptedBaseKey;
    
    private transient SecretKey headerEncKey;
    private transient byte[] headerEncIV;
    private int version; 
    
    private transient MasterSecret masterKey;
    
    private static final long END_MAGIC = 0x2c158a6c7772acd3L;
    private static final int VERSION_AND_MAGIC_LENGTH = 12;
    
    public EncryptedRandomAccessBucket(EncryptedRandomAccessThingType type, 
            RandomAccessBucket underlying, MasterSecret masterKey) {
        this.type = type;
        this.underlying = underlying;
        this.masterKey = masterKey;
        baseSetup(masterKey);
    }

    /** Setup methods that don't depend on the actual key */
    private void baseSetup(MasterSecret masterKey) {
        
        MasterSecret masterSecret = masterKey;
        
        this.headerEncKey = masterSecret.deriveKey(type.encryptKey);
        this.headerMacKey = masterSecret.deriveKey(type.macKey);
        
        version = type.bitmask;
    }
    
    private SkippingStreamCipher setup(OutputStream os) throws GeneralSecurityException, IOException {
        this.headerEncIV = KeyGenUtils.genIV(type.encryptType.ivSize).getIV();
        this.unencryptedBaseKey = KeyGenUtils.genSecretKey(type.encryptKey);
        writeHeader(os);
        setupKeys();
        SkippingStreamCipher cipherWrite = this.type.get();
        cipherWrite.init(true, cipherParams);
        return cipherWrite;
    }
    
    private void writeHeader(OutputStream os) throws GeneralSecurityException, IOException {
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
        
        os.write(header);
    }

    private SkippingStreamCipher setup(InputStream is) throws IOException, GeneralSecurityException {
        byte[] fullHeader = new byte[type.headerLen];
        try {
            new DataInputStream(is).readFully(fullHeader);
        } catch (EOFException e) {
            throw new IOException("Underlying RandomAccessThing is not long enough to include the "
                    + "footer.");
        }
        byte[] header = Arrays.copyOfRange(fullHeader, fullHeader.length-VERSION_AND_MAGIC_LENGTH, 
                fullHeader.length);
        int offset = 0;
        int readVersion = ByteBuffer.wrap(header, offset, 4).getInt();
        offset += 4;
        long magic = ByteBuffer.wrap(header, offset, 8).getLong();
        if(END_MAGIC != magic) {
            throw new IOException("This is not an EncryptedRandomAccessThing!");
        }
        if(readVersion != version){
            throw new IOException("Version of the underlying RandomAccessThing is "
                    + "incompatible with this ERATType");
        }
        if(!verifyHeader(fullHeader))
            throw new GeneralSecurityException("MAC is incorrect");
        setupKeys();
        SkippingStreamCipher cipherRead = this.type.get();
        cipherRead.init(false, cipherParams);
        return cipherRead;
    }
    
    private boolean verifyHeader(byte[] fullHeader) throws IOException, InvalidKeyException {
        byte[] footer = Arrays.copyOfRange(fullHeader, 0, fullHeader.length-VERSION_AND_MAGIC_LENGTH);
        int offset = 0;
        
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

    private void setupKeys() {
        ParametersWithIV tempPram = null;
        try{
            KeyParameter cipherKey = new KeyParameter(KeyGenUtils.deriveSecretKey(unencryptedBaseKey, 
                    EncryptedRandomAccessThing.class, kdfInput.underlyingKey.input, 
                    type.encryptKey).getEncoded());
            tempPram = new ParametersWithIV(cipherKey, 
                    KeyGenUtils.deriveIvParameterSpec(unencryptedBaseKey, EncryptedRandomAccessThing.class, 
                            kdfInput.underlyingIV.input, type.encryptKey).getIV());
        } catch(InvalidKeyException e) {
            throw new IllegalStateException(e); // Must be a bug.
        }
        this.cipherParams = tempPram;
    }
    
    class MyOutputStream extends FilterOutputStream {
        
        private final SkippingStreamCipher cipherWrite;

        public MyOutputStream(OutputStream out, SkippingStreamCipher cipher) {
            super(out);
            this.cipherWrite = cipher;
        }
        
        private final byte[] one = new byte[1];
        
        @Override
        public void write(int x) throws IOException {
            one[0] = (byte)x;
            write(one);
        }
        
        @Override
        public void write(byte[] buf) throws IOException {
            write(buf, 0, buf.length);
        }
        
        @Override
        public void write(byte[] buf, int offset, int length) throws IOException {
            byte[] ciphertext = new byte[length];
            cipherWrite.processBytes(buf, offset, length, ciphertext, 0);
            out.write(ciphertext);
        }

    }
    
    @Override
    public OutputStream getOutputStream() throws IOException {
        if(isFreed){
            throw new IOException("This RandomAccessThing has already been closed. This should not"
                    + " happen.");
        }
        OutputStream uos = underlying.getOutputStream();
        try {
            return new MyOutputStream(uos, setup(uos));
        } catch (GeneralSecurityException e) {
            Logger.error(this, "Unable to create encrypted bucket: "+e, e);
            throw new IOException(e);
        }
    }

    class MyInputStream extends FilterInputStream {
        
        private final SkippingStreamCipher cipherRead;

        public MyInputStream(InputStream in, SkippingStreamCipher cipher) {
            super(in);
            this.cipherRead = cipher;
        }
        
        private byte[] one = new byte[1];
        
        @Override
        public int read() throws IOException {
            int readBytes = read(one);
            if(readBytes <= 0) return readBytes;
            return one[0] & 0xFF;
        }
        
        @Override
        public int read(byte[] buf) throws IOException {
            return read(buf, 0, buf.length);
        }
        
        @Override
        public int read(byte[] buf, int offset, int length) throws IOException {
            int readBytes = in.read(buf, offset, length);
            if(readBytes <= 0) return readBytes;
            cipherRead.processBytes(buf, offset, readBytes, buf, offset);
            return readBytes;
        }
        
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if(size() == 0) return new NullInputStream();
        if(isFreed){
            throw new IOException("This RandomAccessThing has already been closed. This should not"
                    + " happen.");
        }
        InputStream is = underlying.getInputStream();
        try {
            return new MyInputStream(is, setup(is));
        } catch (GeneralSecurityException e) {
            Logger.error(this, "Unable to read encrypted bucket: "+e, e);
            throw new IOException(e);
        }
    }

    @Override
    public String getName() {
        return getClass().getName()+":"+underlying.getName();
    }

    @Override
    public long size() {
        long size = underlying.size();
        if(size == 0) return 0;
        return size - type.headerLen;
    }

    @Override
    public boolean isReadOnly() {
        return underlying.isReadOnly();
    }

    @Override
    public void setReadOnly() {
        underlying.setReadOnly();
    }

    @Override
    public void free() {
        if(isFreed) return;
        isFreed = true;
        underlying.free();
    }

    @Override
    public Bucket createShadow() {
        RandomAccessBucket copy = (RandomAccessBucket) underlying.createShadow();
        return new EncryptedRandomAccessBucket(type, copy, masterKey);
    }

    @Override
    public LockableRandomAccessThing toRandomAccessThing() throws IOException {
        if(underlying.size() < type.headerLen)
            throw new IOException("Converting empty bucket");
        underlying.setReadOnly();
        LockableRandomAccessThing r = underlying.toRandomAccessThing();
        try {
            return new EncryptedRandomAccessThing(type, r, masterKey, false);
        } catch (GeneralSecurityException e) {
            Logger.error(this, "Unable to convert encrypted bucket: "+e, e);
            throw new IOException(e);
        }
    }

}
