package freenet.crypt;

import java.io.File;
import java.io.IOException;

import freenet.support.math.MersenneTwister;

import com.db4o.ext.Db4oIOException;
import com.db4o.io.IoAdapter;

import freenet.crypt.ciphers.Rijndael;
import freenet.node.MasterKeys;
import freenet.support.Fields;
import freenet.support.io.FileUtil;

/**
 * IoAdapter proxy using Rijndael (256 bit key, 256 bit block) in CTR mode.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 * 
 * FIXME: URGENT CRYPTO CODE REVIEW!!!
 */
public class EncryptingIoAdapter extends IoAdapter {
	
	private final IoAdapter baseAdapter;
	private final RandomSource random;
	private final byte[] key;
	private final BlockCipher cipher;
	private long position;
	private byte[] blockOutput;
	private long blockPosition;
	
	static final int BLOCK_SIZE_BYTES = 32;

	/**
	 * @param baseAdapter2
	 * @param databaseKey This is copied, so caller must wipe it.
	 * @param random
	 */
	public EncryptingIoAdapter(IoAdapter baseAdapter2, byte[] databaseKey, RandomSource random) {
		this.baseAdapter = baseAdapter2;
		this.key = new byte[databaseKey.length];
		System.arraycopy(databaseKey, 0, key, 0, databaseKey.length);
		this.random = random;
		position = 0;
		blockPosition = -1;
		blockOutput = new byte[32];
		try {
			cipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new Error(e);
		}
		cipher.initialize(databaseKey);
	}

	@Override
	public void close() throws Db4oIOException {
		baseAdapter.close();
		synchronized(this) {
			MasterKeys.clear(key);
		}
	}

	@Override
	public void delete(String arg0) {
		byte[] seed = new byte[32];
		random.nextBytes(seed);
		try {
			FileUtil.secureDelete(new File(arg0), new MersenneTwister(seed));
		} catch (IOException e) {
			// FIXME useralert?
			// We shouldn't do this ever afaics though, with our usage of db4o...
			System.err.println("Unable to secure delete "+arg0+" for db4o, passing on to underlying adapter "+baseAdapter+" to attempt a normal deletion.");
			baseAdapter.delete(arg0);
		}
	}

	@Override
	public boolean exists(String arg0) {
		return baseAdapter.exists(arg0);
	}

	@Override
	public long getLength() throws Db4oIOException {
		return baseAdapter.getLength();
	}

	@Override
	public IoAdapter open(String arg0, boolean arg1, long arg2, boolean arg3)
			throws Db4oIOException {
		EncryptingIoAdapter adapter =
			new EncryptingIoAdapter(baseAdapter.open(arg0, arg1, arg2, arg3), key, random);
		adapter.seek(0);
		return adapter;
	}

	@Override
	public synchronized int read(byte[] buffer, int length) throws Db4oIOException {
		int readBytes = baseAdapter.read(buffer, length);
		if(readBytes <= 0) return readBytes;
		// CTR mode decryption
		int decrypted = 0;
		int offset = (int) (position % BLOCK_SIZE_BYTES);
		int remaining = BLOCK_SIZE_BYTES - offset;
		int toDecrypt = readBytes;
		while(toDecrypt > 0) {
			int decrypt = Math.min(toDecrypt, remaining);
			long thisBlockPosition = position - offset;
			if(blockPosition != thisBlockPosition) {
				// Encrypt key + position
				byte[] input = new byte[key.length];
				System.arraycopy(key, 0, input, 0, key.length);
				byte[] counter = Fields.longToBytes(thisBlockPosition);
				for(int i=0;i<counter.length;i++)
					input[key.length - 8 + i] ^= counter[i];
				cipher.encipher(input, blockOutput);
				blockPosition = thisBlockPosition;
			}
			for(int i=0;i<decrypt;i++)
				buffer[i+decrypted] ^= blockOutput[i + offset];
			position += decrypt;
			decrypted += decrypt;
			toDecrypt -= decrypt;
			offset = 0;
			remaining = BLOCK_SIZE_BYTES;
		}
		return readBytes;
	}

	@Override
	public synchronized void seek(long arg0) throws Db4oIOException {
		baseAdapter.seek(arg0);
		position = arg0;
	}

	@Override
	public void sync() throws Db4oIOException {
		baseAdapter.sync();
	}

	@Override
	public synchronized void write(byte[] buffer, int length) throws Db4oIOException {
		// CTR mode encryption
		int decrypted = 0;
		int offset = (int) (position % BLOCK_SIZE_BYTES);
		int remaining = BLOCK_SIZE_BYTES - offset;
		int toDecrypt = length;
		while(toDecrypt > 0) {
			int decrypt = Math.min(toDecrypt, remaining);
			long thisBlockPosition = position - offset;
			if(blockPosition != thisBlockPosition) {
				// Encrypt key + position
				byte[] input = new byte[key.length];
				System.arraycopy(key, 0, input, 0, key.length);
				byte[] counter = Fields.longToBytes(thisBlockPosition);
				for(int i=0;i<counter.length;i++)
					input[key.length - 8 + i] ^= counter[i];
				cipher.encipher(input, blockOutput);
				blockPosition = thisBlockPosition;
			}
			for(int i=0;i<decrypt;i++)
				buffer[i+decrypted] ^= blockOutput[i + offset];
			position += decrypt;
			decrypted += decrypt;
			toDecrypt -= decrypt;
			offset = 0;
			remaining = BLOCK_SIZE_BYTES;
		}
		baseAdapter.write(buffer, length);
	}

}
