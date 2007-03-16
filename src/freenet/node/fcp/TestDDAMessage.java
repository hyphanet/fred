/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Random;

import freenet.node.Node;
import freenet.support.HexUtil;
import freenet.support.SimpleFieldSet;

/**
 * TestDDA
 * Identifier=indent123unique	[mandatory]
 * DirToTest=/path/to/dir       [mandatory]			the dir to test
 * TestList=true 		[default: false]  			can we list the dir?
 * ReadFilename=fileindir.ext		the filename for read test
 * 									readtest skipped if missing or empty
 *              the read test file needs to be an existing regular file 
 *              and must have size != 0!
 * WriteFilename=hallo.test			the filename for write test
 * WriteFilename=					the node will generate an unique filename (recommended)
 *									writetest skipped on missing
 * 				if a name is given, the client have to make sure the file doesn't exist!
 * 
 * DeleteTestFile=		[default: true]
 * 				the testfile is only left if the read test was ok. 
 * 
 * 
 * 
 */
public class TestDDAMessage extends FCPMessage {
	
	private short status = -1;
	
	static final String name = "TestDDA";
	
	private boolean resultList = false;
	private boolean resultWrite = false;
	
	private String writeTestFilename = null; // set if it is generated
	private String readResult = null;
	private String writeResult = null;
	final boolean testlist;
	final String identifier;
	final String dir2test;
	final String readfilename;
	final String writefilename;
	
	private final boolean deleteFile;

	/** 
	 * @throws MessageInvalidException 
	 */
	public TestDDAMessage(SimpleFieldSet fs) throws MessageInvalidException {
		identifier = fs.get("Identifier");
		if(identifier == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", null, false);
	
		dir2test = fs.get("DirToTest");
		if(dir2test == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "No Identifier", identifier, false);
		if(dir2test.trim().length() == 0)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "DirToTest can't be empty!", identifier, false);
	
		String rfn = fs.get("ReadFilename");
		if(rfn != null)
			if (rfn.trim().length() > 0)
				readfilename = rfn;
			else
				throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Read test filename can't be empty!", identifier, false);
		else readfilename = null;
		
		String wfn = fs.get("WriteFilename");
		
		if (wfn == null) 
			writefilename = null;
		else 
			writefilename = wfn;
		
		deleteFile = fs.getBoolean("DeleteTestFile", true);
	
		testlist = fs.getBoolean("TestList", false);
	}

	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("TestedDir", dir2test);
		fs.putSingle("Status", getStatus());
		if (status == 0) { 
			if (testlist)
				fs.putSingle("ListTest", getResultName(resultList));
			else
				fs.putSingle("ListTest", "Skipped");
			
			if (readfilename != null) {
				if (readResult == null) { 
					fs.putSingle("ReadTest", getResultName(false));
				} else {
					fs.putSingle("ReadTest", getResultName(true));
					fs.putSingle("ReadResult", readResult);
				}					
			} else
				fs.putSingle("ReadTest", "Skipped");
			
			if (writefilename != null) {
				if (writeTestFilename != null) {
					fs.putSingle("WriteFileName", writeTestFilename);
				} else {
					fs.putSingle("WriteFileName", writefilename);
				}
				fs.putSingle("WriteData", writeResult);
				fs.putSingle("WriteTest", getResultName(resultWrite));
			} else
				fs.putSingle("WriteTest", "Skipped");
		} else if (status == -1) {
			fs.putSingle("Description", "Test not started");
		} else if (status == 1) {
			fs.putSingle("Description", "Dir doesn't exist or isn't acccessible");
		} else 
			fs.putSingle("Description", "Unknown status? please report");
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		realTest();
		handler.outputHandler.queue(this);
	}
	
	private void realTest() throws MessageInvalidException {
		
		// first test dir itself
		File dir = new File(dir2test);
		
		if (!dir.isDirectory()) {
			status = 1;
			return;
		}
		
		
		// can list dir?
		FilenameFilter dirLimiter = new FilenameFilter() {
				private int limit = 10;

				public boolean accept(File dir, String name) {
					if (limit <1) return false;
					limit--;
					return true;
				}
			};

		String[] t_dirList = dir.list(dirLimiter); 
		resultList = (t_dirList != null);

		// read
		if (readfilename != null) {
		
			File t_read = new File(dir, readfilename);
			
			if (!t_read.isFile())
				throw new MessageInvalidException(ProtocolErrorMessage.FILE_NOT_FOUND, "Read test filename must be an existing regular file!", identifier, false);
		
			if (t_read.length() == 0)
				throw new MessageInvalidException(ProtocolErrorMessage.NOT_SUPPORTED, "Read test file must have content! File size is null!", identifier, false);

			// read first max 8 bytes
			try {
				FileInputStream fis = new FileInputStream(t_read);
				byte[] b = new byte[8];
				int l;
				try {
					l = fis.read(b, 0, 8);
					readResult = HexUtil.bytesToHex(b, 0 ,l);
				} catch (IOException e) {
				} 
				fis.close();
			} catch (FileNotFoundException e) {
			} catch (IOException e) {
			}
		}
	
		// write
		if (writefilename != null) {
			
			Random r = new Random();
			byte[] b = new byte[8];
			
			r.nextBytes(b);
			
			File f;
			try {
				if (writefilename.trim().length() == 0) {
					//generate one
					f = File.createTempFile("NodeDDAtest", ".dat", dir);
					writeTestFilename = f.getName();
				} else { 
					f = new File(dir, writefilename);
					if (f.exists()) {
						throw new MessageInvalidException(ProtocolErrorMessage.DISK_TARGET_EXISTS, "Write test target file can't exist!", identifier, false);
					}
				}
			
				f.deleteOnExit();
			
				FileOutputStream fos = new FileOutputStream(f);
				fos.write(b);
				fos.close();
			
				FileInputStream fis = new FileInputStream(f);
			
				byte[] bb = new byte[8];

				fis.read(bb);
				fis.close();
			
				writeResult= HexUtil.bytesToHex(bb, 0 ,8);
				resultWrite = Arrays.equals(b, bb);
				
				if (deleteFile) 
					f.delete();
				
			} catch (IOException ioe) {
			}
		}
		status = 0;
	}
	
	private String getStatus() {
		if (status == 0) return "Done";
		if (status > 0) return "Failed";
		return "Unknown";
	}
	
	private String getResultName(boolean rn) {
		if (rn) return "OK";
		return "Failed";
	}

}
