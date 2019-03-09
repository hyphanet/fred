/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Filter text/plain, because if firefox encounters non-text bytes at
 * the beginning, it guesses the MIME type.  Text bytes are 9-13,
 * 27, and 31-255.  See
 * https://developer.mozilla.org/en-US/docs/Mozilla/How_Mozilla_determines_MIME_Types#HTTP
 * 
 * This filter replaces all non-text bytes by the value for the
 * integer representation of the byte value.
 */
public class TXTFilter implements ContentDataFilter {


	private int unsignedByte(byte b)
	{
		if (b >= 0)
			return b;
		else
			return 256+b;
	}

    private boolean isBinaryByteValue(int i)
    {
        // Text bytes are 9-13, 27, and 31-255.
        if (i < 9) {
            return true;
        } else if (i > 13 && i < 27) {
            return true;
        } else if (i > 27 && i < 31) {
            return true;
        }
        return false;
    }


	@Override
	public void readFilter(InputStream input, OutputStream output, String charset, HashMap<String, String> otherParams,
			FilterCallback cb) throws DataFilterException, IOException {
        byte[] nextbyte = new byte[1];
        int value;
	int readcount;
        DataInputStream dis = new DataInputStream(input);
        DataOutputStream dos = new DataOutputStream(output);

        readcount = dis.read(nextbyte);
        while (readcount != -1) {
            value = unsignedByte(nextbyte[0]);
            if (isBinaryByteValue(value)) {
                dos.write(new byte[] {(byte) Integer.toString(value).charAt(0)});
            } else {
                dos.write(nextbyte);
            }
        }
        dos.flush();
        dos.close();
		output.flush();
	}

}
