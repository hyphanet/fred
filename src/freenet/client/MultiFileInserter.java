package freenet.client;

import java.io.IOException;
import java.util.HashMap;

import freenet.keys.FreenetURI;
import freenet.support.ArrayBucket;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

public class MultiFileInserter {

	public class MFInserter implements Runnable {

		final int num;

		MFInserter(int x) {
			num = x;
		}
		
		public void run() {
			try {
			while(true) {
				String name = null;
				Bucket data = null;
				synchronized(bucketsByName) {
					if(bucketsByName.isEmpty()) break;
					name = (String) bucketsByName.keySet().iterator().next();
					data = (Bucket) bucketsByName.remove(name);
				}
				String mimeType = DefaultMIMETypes.guessMIMEType(name);
				Logger.minor(this, "Name: "+name+"\nBucket size: "+data.size()+"\nGuessed MIME type: "+mimeType);
				byte[] metaByteArray;
				try {
					metaByteArray = getMetadata(name, data, mimeType);
				} catch (InserterException e) {
					Logger.normal(this, "Error inserting "+name+": "+e.getMessage());
					errorCodes.inc(e.getMode());
					synchronized(this) {
						errors++;
					}
					continue;
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t);
					errorCodes.inc(InserterException.INTERNAL_ERROR);
					synchronized(this) {
						errors++;
					}
					continue;
				}
				if(metaByteArray != null) {
					synchronized(namesToMetadataByteArrays) {
						namesToMetadataByteArrays.put(name, metaByteArray);
					}
					Logger.minor(this, "Inserted "+name);
				} else {
					Logger.normal(this, "Insert failed: "+name);
				}

			}
		} finally {
			synchronized(MultiFileInserter.this) {
				finished[num] = true;
				MultiFileInserter.this.notifyAll();
			}
		}
		}

	}

	final FreenetURI targetURI;
	final HashMap bucketsByName;
	final InserterContext ctx;
	final String defaultName;
	final HashMap namesToMetadataByteArrays;
	final FailureCodeTracker errorCodes;
	private int errors;
	private final boolean[] finished;
	
	public MultiFileInserter(FreenetURI insertURI, HashMap bucketsByName, InserterContext context, String defaultName) {
		this.targetURI = insertURI;
		this.bucketsByName = bucketsByName;
		this.ctx = context;
		this.defaultName = defaultName;
		this.namesToMetadataByteArrays = new HashMap();
		this.errorCodes = new FailureCodeTracker(true);
		if(bucketsByName.get(defaultName) == null)
			// FIXME make this an InserterException.
			throw new IllegalArgumentException();
		finished = new boolean[5];
	}

	public FreenetURI run() throws InserterException {
		// For each file, guess MIME type, insert it, get the metadata.
		// Then put all the metadata at once into one manifest.
		// Then return it.

		// FIXME scaling issues; have to keep everything in RAM...
		
		for(int j=0;j<finished.length;j++) {
			MFInserter it = new MFInserter(j);
			Thread t = new Thread(it, "Inserter #"+j);
			t.setDaemon(true);
			t.start();
		}
		
		synchronized(this) {
			while(true) {
				boolean stillRunning = false;
				for(int i=0;i<finished.length;i++) {
					if(!finished[i]) stillRunning = true;
				}
				if(!stillRunning) break;
				try {
					wait(10000);
				} catch (InterruptedException e) {
					// Impossible??
				}
			}
		}
		
		if(defaultName != null) {
			synchronized(namesToMetadataByteArrays) {
				byte[] defaultData = (byte[]) namesToMetadataByteArrays.get(defaultName);
				if(defaultData != null)
					namesToMetadataByteArrays.put("", defaultData);
				else {
					Logger.error(this, "Default name "+defaultName+" does not exist");
					if(namesToMetadataByteArrays.containsKey(defaultName))
						Logger.error(this, "Default name exists but has null bytes!");
					// It existed ... and now it doesn't?!
					throw new InserterException(InserterException.INTERNAL_ERROR);
				}
			}
		}

		Metadata manifestMetadata = Metadata.mkRedirectionManifestWithMetadata(namesToMetadataByteArrays);
		
		Bucket metadata = new ArrayBucket(manifestMetadata.writeToByteArray());
		
		FileInserter fi = new FileInserter(ctx);
		
		InsertBlock block = new InsertBlock(metadata, null, targetURI);
		
		FreenetURI uri = fi.run(block, true, false, false, null);
		
		if(errors > 0) {
			throw new InserterException(InserterException.FATAL_ERRORS_IN_BLOCKS, errorCodes, uri);
		}
		
		return uri;
	}

	private byte[] getMetadata(String name, Bucket data, String mimeType) throws InserterException {
		FileInserter fi = new FileInserter(ctx);
		InsertBlock block = new InsertBlock(data, new ClientMetadata(mimeType), FreenetURI.EMPTY_CHK_URI);
		ArrayBucket metaBucket = new ArrayBucket();
		FreenetURI uri;
		// FIXME make a client event and switch this to logger.log(...)
		System.out.println("Inserting "+name+" ("+data.size()+" bytes, "+mimeType+")");
		try {
			uri = fi.run(block, false, false, false, metaBucket);
		} catch (InserterException e1) {
			if(e1.uri != null && e1.getMode() == InserterException.COLLISION || e1.getMode() == InserterException.ROUTE_NOT_FOUND || e1.getMode() == InserterException.ROUTE_REALLY_NOT_FOUND) {
				Logger.minor(this, "Ignoring "+e1);
				uri = e1.uri;
			} else {
				// Clear the uri.
				throw new InserterException(e1.getMode());
			}
		}
		byte[] metaByteArray;
		if(metaBucket.size() == 0) {
			// It didn't give us any metadata
			Logger.minor(this, "Did not return metadata: creating our own");
			Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, uri, null);
			metaByteArray = m.writeToByteArray();
			if(metaByteArray == null) throw new NullPointerException();
		} else {
			try {
				metaByteArray = BucketTools.toByteArray(metaBucket);
				if(metaByteArray == null) throw new NullPointerException();
			} catch (IOException e) {
				throw new Error(e);
			}
		}
		return metaByteArray;
	}
	
}
