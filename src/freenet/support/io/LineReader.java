package freenet.support.io;

import java.io.IOException;

public interface LineReader {

	/**
	 * Read a \n or \r\n terminated line of UTF-8 or ISO-8859-1.
	 */
	public String readLine(int maxLength, int bufferSize, boolean utf) throws IOException;
	
}
