/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;

import freenet.node.fcp.MessageInvalidException;
import freenet.node.fcp.TestDDAResult;


/**
 * the testmanager?
 * 
 */
public class TestDDAManager  {
	
	/** 
	 * @throws MessageInvalidException 
	 */
	TestDDAManager() {
	}

	/**
	 * @param ident
	 * @return
	 */
	public File getTestWriteFile(String ident) {
		return null;
	}

	
	/**
	 * @param _ident
	 * @return
	 */
	public TestDDAResult getTestResult(String _ident) {
		return null;
	}


}
