package freenet.crypt;

import java.io.*;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Assuming you have a sufficiently long file containing random data that 
 * you wish to use (i.e. a unix random device), this wraps that file
 * into the RandomSource interface
 */
public class RandFile extends RandStream {
    
    public RandFile(String filename) {
	this(new File(filename));
    }

    public RandFile(File filename) {
	try {
	    stream=new DataInputStream(new FileInputStream(filename));
	} catch (Exception e) {
	    stream=null; // FORCES fallback 
	}
    }
}
