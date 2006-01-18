package freenet.support.io;

import java.io.IOException;

public interface LineReader {

	public String readLine(int maxLength, int bufferSize) throws IOException;
	
}
