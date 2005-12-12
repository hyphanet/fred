package freenet.store;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import com.sleepycat.je.BtreeStats;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryConfig;
import com.sleepycat.je.SecondaryDatabase;
import com.sleepycat.je.SecondaryKeyCreator;
import com.sleepycat.je.Transaction;

import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.Fields;
import freenet.support.Logger;

/** 
 * Freenet datastore based on BerkelyDB Java Edition by sleepycat software
 * More info at http://www.sleepycat.com/products/bdbje.html
 * 
 * @author tubbie
 * 
 * TODO: Fix ugly Exception handling
 */
public class BerkelyDBFreenetStore implements FreenetStore {

	private final Environment environment;
	private final TupleBinding storeBlockTupleBinding;
	private final TupleBinding longTupleBinding;
	private final Database chkDB;
	private final Database chkDB_accessTime;
	
	private boolean closed = false;
	
	private final StoreDeleter storeDeleter;
	
	private final int maxBlocks;
	
	/**
     * Initializes database
     * @param the directory where the store is located
     * @throws FileNotFoundException if the dir does not exist and could not be created
     */
	public BerkelyDBFreenetStore(String storeDir,int maxBlocks) throws Exception
	{
		// Percentage of the database that must contain usefull data
		// decrease to increase performance, increase to save disk space
		System.setProperty("je.cleaner.minUtilization","98");
		
		// Delete empty log files
		System.setProperty("je.cleaner.expunge","true");
		
		// Percentage of the maximum heap size used as a cache
		System.setProperty("je.maxMemoryPercent","30");
		
		this.maxBlocks=maxBlocks;
		
		// Initialize environment
		EnvironmentConfig envConfig = new EnvironmentConfig();
		envConfig.setAllowCreate(true);
		envConfig.setTransactional(true);
		envConfig.setTxnWriteNoSync(true);
		File dir = new File(storeDir);
		if(!dir.exists())
			dir.mkdir();

		environment = new Environment(new File(storeDir), envConfig);
		
		// Initialize CHK database
		DatabaseConfig dbConfig = new DatabaseConfig();
		dbConfig.setAllowCreate(true);
		dbConfig.setTransactional(true);
		chkDB = environment.openDatabase(null,"CHK",dbConfig);
				
		// Initialize secondary CHK database sorted on accesstime
		SecondaryConfig secDbConfig = new SecondaryConfig();
		secDbConfig.setAllowCreate(true);
		secDbConfig.setSortedDuplicates(true);
		secDbConfig.setTransactional(true);
		storeBlockTupleBinding = new StoreBlockTupleBinding();
		longTupleBinding = TupleBinding.getPrimitiveBinding(Long.class);
		AccessTimeKeyCreator accessTimeKeyCreator = 
			new AccessTimeKeyCreator(storeBlockTupleBinding);
		secDbConfig.setKeyCreator(accessTimeKeyCreator);
		chkDB_accessTime = environment.openSecondaryDatabase
							(null, "CHK_accessTime", chkDB, secDbConfig);
		
		// Initialize thread that deletes items when the store gets full
		storeDeleter = new StoreDeleter();
		(new Thread(storeDeleter,"BDBStore: StoreDeleter")).start();
		
		// Add shutdownhook
		Runtime.getRuntime().addShutdownHook(new ShutdownHook());
	}
	
	/**
     * Retrieve a block.
     * @param dontPromote If true, don't promote data if fetched.
     * @return null if there is no such block stored, otherwise the block.
     */
    public CHKBlock fetch(NodeCHK chk, boolean dontPromote) throws IOException
    {
    	if(closed)
    		return null;
    	
    	byte[] routingkey = chk.getRoutingKey();
    	DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
    	DatabaseEntry blockDBE = new DatabaseEntry();
    	Cursor c = null;
    	try{
    		Transaction t = environment.beginTransaction(null,null);
    		c = chkDB.openCursor(t,null);
    		
    		if(c.getSearchKey(routingkeyDBE,blockDBE,LockMode.DEFAULT)
    				!=OperationStatus.SUCCESS) {
    			return null;
    		}

	    	StoreBlock storeBlock = (StoreBlock) storeBlockTupleBinding.entryToObject(blockDBE);
	    		    	
	    	CHKBlock block = null;
	    	try{
	    		block = new CHKBlock(storeBlock.getData(),storeBlock.getHeader(),chk);
	    		
	    		if(!dontPromote)
	    		{
	    			storeBlock.updateAccessTime();
	    			DatabaseEntry updateDBE = new DatabaseEntry();
	    			storeBlockTupleBinding.objectToEntry(storeBlock, updateDBE);
	    			c.putCurrent(updateDBE);
		    		c.close();
		    		t.commit();
	    		}
	    		
	    		Logger.minor(this, "Get key: "+chk);
	            Logger.minor(this, "Headers: "+storeBlock.getHeader().length+" bytes, hash "+Fields.hashCode(storeBlock.getHeader()));
	            Logger.minor(this, "Data: "+storeBlock.getData().length+" bytes, hash "+Fields.hashCode(storeBlock.getData()));
	    		
	    	}catch(CHKVerifyException ex){
	    		Logger.normal(this, "Does not verify, deleting: "+chk);
	    		c.delete();
	    		c.close();
	    		t.abort();
	    		storeDeleter.deletedItem();
	            return null;
	    	}
	    	return block;
    	}catch(DatabaseException ex) {  // FIXME: ugly  
    		if(c!=null)
    			try{c.close();}catch(DatabaseException ex2){}
        	new IOException(ex.getMessage());
        }
    	
    	return null;
    }

    /**
     * Store a block.
     */
    public void put(CHKBlock block) throws IOException
    {   	
    	if(closed)
    		return;
    	
    	byte[] routingkey = ((NodeCHK)block.getKey()).getRoutingKey();
        byte[] data = block.getData();
        byte[] header = block.getHeader();
        StoreBlock storeBlock = new StoreBlock(data,header);

        Transaction t = null;
        try{
	        DatabaseEntry routingkeyDBE = new DatabaseEntry(routingkey);
	        
	    	DatabaseEntry blockDBE = new DatabaseEntry();
	    	storeBlockTupleBinding.objectToEntry(storeBlock, blockDBE);
	    	
	    	t = environment.beginTransaction(null,null); 
	    	Logger.minor(this, "Put key: "+block.getKey());
	        Logger.minor(this, "Headers: "+header.length+" bytes, hash "+Fields.hashCode(header));
	        Logger.minor(this, "Data: "+data.length+" bytes, hash "+Fields.hashCode(data));
	        chkDB.put(t,routingkeyDBE,blockDBE);
	        t.commit();
	        
	        storeDeleter.addedItem();
	        
        }catch(Exception ex) {  // FIXME: ugly  
        	if(t!=null){
        		try{t.abort();}catch(DatabaseException ex2){};
        	}
        	ex.printStackTrace();
        	new IOException(ex.getMessage());
        }
    }
    
    private class StoreBlock
    {
    	private final byte[] data, header;
    	private long lastAccessed;
    	public StoreBlock(byte[] data,byte[] header)
    	{
    		this(data,header,System.currentTimeMillis());
    	}
    	
    	public StoreBlock(byte[] data,byte[] header,long lastAccessed)
    	{
    		this.data=data;
    		this.header=header;
    		this.lastAccessed = lastAccessed;
    	}
    	
    	public byte[] getData()		{return data;}
    	public byte[] getHeader()	{return header;}
    	
    	public void updateAccessTime() {
    		lastAccessed = System.currentTimeMillis();
    	}
    	
    	public long getLastAccessed()
    	{
    		return lastAccessed;
    	}
    }
        
    /**
     * Convert StoreBlock's to the format used by the database
     */
    private class StoreBlockTupleBinding extends TupleBinding
    {

    	public void objectToEntry(Object object, TupleOutput to)  {
    		StoreBlock myData = (StoreBlock)object;

    		writeByteArray(myData.getHeader(),to);
    		writeByteArray(myData.getData(),to);
    		to.writeLong(myData.getLastAccessed());

    	}

    	public Object entryToObject(TupleInput ti) {
	    	byte[] header = readByteArray(ti);
	    	byte[] data = readByteArray(ti);
	    	long lastAccessed = ti.readLong();
	    	
	    	StoreBlock storeBlock = new StoreBlock(data,header,lastAccessed);
	    	return storeBlock;
    	}
    	
    	private byte[] readByteArray(TupleInput ti) {
    		try{
	    		char size = ti.readChar();
	    		byte[] data = new byte[size];
	    		char read = 0;
	    		while(read<size)
	    			read +=ti.read(data,read,size-read);
	    		
	    		return data;
    		}catch(IOException ex){}
    		return null;
    	}
    	
    	private void writeByteArray(byte[] data,TupleOutput to)
    	{
    		try {
	    		to.writeChar(data.length);
	    		to.write(data);
    		}catch(IOException ex){}
    	}
    }
      
    /**
     * Used to create the secondary database sorted on accesstime
     */
    private class AccessTimeKeyCreator implements SecondaryKeyCreator {
    	private TupleBinding theBinding;
    	
    	public AccessTimeKeyCreator(TupleBinding theBinding1) {
    		theBinding = theBinding1;
    	}
    	
    	public boolean createSecondaryKey(SecondaryDatabase secDb,
    	DatabaseEntry keyEntry,
    	DatabaseEntry dataEntry,
    	DatabaseEntry resultEntry) {

    		StoreBlock storeblock = (StoreBlock) theBinding.entryToObject(dataEntry);
    		Long accessTime = new Long(storeblock.getLastAccessed());
    		longTupleBinding.objectToEntry(accessTime, resultEntry);
    		return true;
    	}
    }

    /** 
     * Cleans items from the store when it's full
     */
    private class StoreDeleter implements Runnable
    {
    	private boolean running=true;
    	private boolean stopped=false;
    	private int currentItems;
    	
    	public StoreDeleter() {
    		currentItems = countCHKBlocks();
    	}
    	
    	
    	
    	public synchronized void addedItem() {
    		currentItems++;
    		this.notify();
    	}
    	
    	public synchronized void deletedItem() {
    		currentItems--;
    	}
    	
    	public void run() {
    		while(running){  	
    			synchronized(this) {
	    			while(currentItems < maxBlocks && running)
	        			try{wait();}catch(InterruptedException ex){}
    			}
        		while(currentItems>maxBlocks){
        			removeOldestItem();
        		}
    		}

    		stopped=true;
    		synchronized(this){
    			this.notify();
    		}
    	}
    	
    	private void removeOldestItem() {
        	Logger.normal(this, "Deleting oldest item in store");
        	Cursor c = null;
        	try{
        		Transaction t = environment.beginTransaction(null,null);
        		c = chkDB_accessTime.openCursor(t,null);
        		
        		DatabaseEntry keyDBE = new DatabaseEntry();
        		DatabaseEntry dataDBE = new DatabaseEntry();
        		
        		if(c.getFirst(keyDBE,dataDBE,null).equals(OperationStatus.SUCCESS))
        		{
        			c.delete();
        			c.close();
        			t.commit();
        			deletedItem();
        		}else{
        			t.abort();
        		}

        		
        	}catch(Exception ex) {
        		ex.printStackTrace();
        		if(c!=null)
        			try{c.close();}catch(DatabaseException ex2){}
       		}
        }
    	
    	public synchronized void close() {
    		running = false;
    		this.notify();
    		
    		// Block till we actually closed
    		while(!stopped)
    			try{wait();}catch(InterruptedException ex){}
    	}
    }

    private class ShutdownHook extends Thread
    {
    	public void run() {
    		close();
    	}
    }
    
    private void close() {
    	try{
			// FIXME: 	we should be sure all access to the database has stopped
			//			before we try to close it. Currently we just guess
    		//			This is nothing too problematic however since the worst thing that should
    		//			happen is that we miss the last few store()'s and get an exception.
			Logger.minor(this, "Closing database.");
			storeDeleter.close();
			closed=true;
			// Give all threads some time to complete
			Thread.sleep(3000);
    		chkDB_accessTime.close();
    		chkDB.close();
    		environment.close();
    		Logger.minor(this, "Closing database finished.");
		}catch(Exception ex){
			Logger.error(this,"Error while closing database.",ex);
			ex.printStackTrace();
		}
    }
    
    private int countCHKBlocks() {
    	int count =0;
    	try{
    		Logger.minor(this, "Started counting items in database");
    		
    		count = (int)((BtreeStats)chkDB.getStats(null)).getLeafNodeCount();
    		
    		Logger.minor(this, "Counted "+count+" items in database");
    	}catch(DatabaseException ex){
    		Logger.minor(this, "Exception while counting items in database",ex);
    	}
    	return count;
    }
}