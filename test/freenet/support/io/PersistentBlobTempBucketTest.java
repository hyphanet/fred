package freenet.support.io;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import com.db4o.Db4o;
import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.DBJobRunner;
import freenet.client.async.DatabaseDisabledException;
import freenet.client.async.TrivialDBJobRunner;
import freenet.crypt.DummyRandomSource;
import freenet.support.Executor;
import freenet.support.PooledExecutor;
import freenet.support.Ticker;
import freenet.support.TrivialTicker;
import freenet.support.api.Bucket;
import freenet.support.math.MersenneTwister;

import junit.framework.TestCase;

public class PersistentBlobTempBucketTest extends TestCase {

	public void testSimple() throws IOException, DatabaseDisabledException {
		checkSimple(1024, 1024, 10);
	}
	
	public void testSimpleNotFull() throws IOException, DatabaseDisabledException {
		checkSimple(1024, 512, 10);
	}
	
	public void checkSimple(int blockSize, int dataSize, int blocks) throws IOException, DatabaseDisabledException {
		File database = File.createTempFile("persistent-blob-test", ".db4o");
		File store = File.createTempFile("persistent-blob-test", ".blob");
		database.delete();
		store.delete();
		ObjectContainer container =
			Db4o.openFile(database.getPath());
		MersenneTwister fastWeakRandom = new MersenneTwister(1234);
		final Executor exec = new PooledExecutor();
		//ClientContext context = new ClientContext(-1, -1, jobRunner, )
		final TrivialDBJobRunner jobRunner = new TrivialDBJobRunner(container);
		final TrivialTicker ticker = new TrivialTicker(exec);
		ClientContext context = new ClientContext(-1, -1, jobRunner, null, exec, 
				null, null, 
				null, null, null, null,
				null, new DummyRandomSource(12345),
				fastWeakRandom, ticker, 
				null, null, null, null);
		jobRunner.start(exec, context);
		PersistentBlobTempBucketFactory factory = new PersistentBlobTempBucketFactory(blockSize, -1, store);
		factory.onInit(container, jobRunner, fastWeakRandom, store, blockSize, ticker);
		
		Bucket[] buckets = new Bucket[blocks];
		byte[][] bufs = new byte[blocks][];
		
		for(int i=0;i<blocks;i++) {
			bufs[i] = new byte[blockSize];
			fastWeakRandom.nextBytes(bufs[i]);
			buckets[i] = factory.makeBucket();
			OutputStream os = buckets[i].getOutputStream();
			os.write(bufs[i]);
			os.close();
			buckets[i].storeTo(container);
			assert(buckets[i].size() == blockSize);
			assertEquals(factory.lastOccupiedBlock(), i);
		}
		
		for(int i=0;i<blocks;i++) {
			DataInputStream dis = new DataInputStream(buckets[i].getInputStream());
			byte[] check = new byte[blockSize];
			dis.readFully(check);
			dis.close();
			assert(Arrays.equals(bufs[i], check));
		}
		
		container.close();
	}
	
	public void testDelete() throws IOException, DatabaseDisabledException {
		checkDelete(1024, 1024, 10);
	}

	public void checkDelete(int blockSize, int dataSize, int blocks) throws IOException, DatabaseDisabledException {
		File database = File.createTempFile("persistent-blob-test", ".db4o");
		File store = File.createTempFile("persistent-blob-test", ".blob");
		database.delete();
		store.delete();
		ObjectContainer container =
			Db4o.openFile(database.getPath());
		MersenneTwister fastWeakRandom = new MersenneTwister(1234);
		final Executor exec = new PooledExecutor();
		//ClientContext context = new ClientContext(-1, -1, jobRunner, )
		final TrivialDBJobRunner jobRunner = new TrivialDBJobRunner(container);
		final TrivialTicker ticker = new TrivialTicker(exec);
		ClientContext context = new ClientContext(-1, -1, jobRunner, null, exec, 
				null, null, 
				null, null, null, null,
				null, new DummyRandomSource(12345),
				fastWeakRandom, ticker, 
				null, null, null, null);
		jobRunner.start(exec, context);
		PersistentBlobTempBucketFactory factory = new PersistentBlobTempBucketFactory(blockSize, -1, store);
		factory.onInit(container, jobRunner, fastWeakRandom, store, blockSize, ticker);
		
		Bucket[] buckets = new Bucket[blocks];
		byte[][] bufs = new byte[blocks][];
		
		for(int i=0;i<blocks;i++) {
			bufs[i] = new byte[blockSize];
			fastWeakRandom.nextBytes(bufs[i]);
			buckets[i] = factory.makeBucket();
			OutputStream os = buckets[i].getOutputStream();
			os.write(bufs[i]);
			os.close();
			assert(buckets[i].size() == blockSize);
			buckets[i].storeTo(container);
			assertEquals(factory.lastOccupiedBlock(), i);
		}
		
		for(int i=blocks-1;i>=0;i--) {
			DataInputStream dis = new DataInputStream(buckets[i].getInputStream());
			byte[] check = new byte[blockSize];
			dis.readFully(check);
			dis.close();
			assert(Arrays.equals(bufs[i], check));
			buckets[i].free();
			buckets[i].removeFrom(container);
			assertEquals(factory.lastOccupiedBlock(), i - 1);
		}
		
		container.close();
	}

	public void testDefrag() throws IOException, DatabaseDisabledException {
		checkDefrag(1024, 1024, 10);
	}

	public void checkDefrag(int blockSize, int dataSize, int blocks) throws IOException, DatabaseDisabledException {
		File database = File.createTempFile("persistent-blob-test", ".db4o");
		File store = File.createTempFile("persistent-blob-test", ".blob");
		database.delete();
		store.delete();
		ObjectContainer container =
			Db4o.openFile(database.getPath());
		MersenneTwister fastWeakRandom = new MersenneTwister(1234);
		final Executor exec = new PooledExecutor();
		//ClientContext context = new ClientContext(-1, -1, jobRunner, )
		final TrivialDBJobRunner jobRunner = new TrivialDBJobRunner(container);
		final TrivialTicker ticker = new TrivialTicker(exec);
		ClientContext context = new ClientContext(-1, -1, jobRunner, null, exec, 
				null, null, 
				null, null, null, null,
				null, new DummyRandomSource(12345),
				fastWeakRandom, ticker, 
				null, null, null, null);
		jobRunner.start(exec, context);
		PersistentBlobTempBucketFactory factory = new PersistentBlobTempBucketFactory(blockSize, -1, store);
		factory.onInit(container, jobRunner, fastWeakRandom, store, blockSize, ticker);
		
		Bucket[] buckets = new Bucket[blocks];
		byte[][] bufs = new byte[blocks][];
		PersistentBlobTempBucketFactory.DISABLE_SANITY_CHECKS_DEFRAG = true;
		
		for(int i=0;i<blocks;i++) {
			bufs[i] = new byte[blockSize];
			fastWeakRandom.nextBytes(bufs[i]);
			buckets[i] = factory.makeBucket();
			OutputStream os = buckets[i].getOutputStream();
			os.write(bufs[i]);
			os.close();
			assert(buckets[i].size() == blockSize);
			buckets[i].storeTo(container);
			container.commit();
			factory.postCommit();
			assertEquals(factory.lastOccupiedBlock(), i);
		}
		
		int lastSlot = blocks - 1;
		assertEquals(factory.lastOccupiedBlock(), lastSlot);
		
		for(int i=0;i<blocks;i++) {
			DataInputStream dis = new DataInputStream(buckets[i].getInputStream());
			byte[] check = new byte[blockSize];
			dis.readFully(check);
			dis.close();
			assert(Arrays.equals(bufs[i], check));
			assertEquals(factory.lastOccupiedBlock(), lastSlot);
			buckets[i].free();
			buckets[i].removeFrom(container);
			container.commit();
			factory.postCommit();
			factory.maybeShrink(container);
			assertEquals(factory.lastOccupiedBlock(), --lastSlot);
		}
		
		container.close();
	}
	
	public void testDefragStillOpen() throws IOException, DatabaseDisabledException {
		checkDefragStillOpen(1024, 1024, 10);
	}

	public void checkDefragStillOpen(int blockSize, int dataSize, int blocks) throws IOException, DatabaseDisabledException {
		File database = File.createTempFile("persistent-blob-test", ".db4o");
		File store = File.createTempFile("persistent-blob-test", ".blob");
		database.delete();
		store.delete();
		ObjectContainer container =
			Db4o.openFile(database.getPath());
		MersenneTwister fastWeakRandom = new MersenneTwister(1234);
		final Executor exec = new PooledExecutor();
		//ClientContext context = new ClientContext(-1, -1, jobRunner, )
		final TrivialDBJobRunner jobRunner = new TrivialDBJobRunner(container);
		final TrivialTicker ticker = new TrivialTicker(exec);
		ClientContext context = new ClientContext(-1, -1, jobRunner, null, exec, 
				null, null, 
				null, null, null, null,
				null, new DummyRandomSource(12345),
				fastWeakRandom, ticker, 
				null, null, null, null);
		jobRunner.start(exec, context);
		PersistentBlobTempBucketFactory factory = new PersistentBlobTempBucketFactory(blockSize, -1, store);
		factory.onInit(container, jobRunner, fastWeakRandom, store, blockSize, ticker);
		
		Bucket[] buckets = new Bucket[blocks];
		byte[][] bufs = new byte[blocks][];
		DataInputStream[] is = new DataInputStream[blocks];
		PersistentBlobTempBucketFactory.DISABLE_SANITY_CHECKS_DEFRAG = true;
		
		for(int i=0;i<blocks;i++) {
			bufs[i] = new byte[blockSize];
			fastWeakRandom.nextBytes(bufs[i]);
			buckets[i] = factory.makeBucket();
			OutputStream os = buckets[i].getOutputStream();
			os.write(bufs[i]);
			os.close();
			assert(buckets[i].size() == blockSize);
			buckets[i].storeTo(container);
			container.commit();
			factory.postCommit();
			assertEquals(factory.lastOccupiedBlock(), i);
			is[i] = new DataInputStream(buckets[i].getInputStream());
		}
		
		int lastSlot = blocks - 1;
		
		for(int i=0;i<blocks;i++) {
			byte[] check = new byte[blockSize];
			is[i].readFully(check);
			is[i].close();
			assert(Arrays.equals(bufs[i], check));
			buckets[i].free();
			buckets[i].removeFrom(container);
			container.commit();
			factory.postCommit();
			factory.maybeShrink(container);
			assertEquals(factory.lastOccupiedBlock(), --lastSlot);
		}
		
		container.close();
	}

}
