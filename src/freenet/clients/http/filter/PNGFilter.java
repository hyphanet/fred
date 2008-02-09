/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.FileBucket;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.zip.CRC32;

/**
 * Content filter for PNG's.
 * This one just verifies that a PNG is valid, and throws if it isn't.
 *
 * It can strip the timestamp and "text(.)*" chunks if asked to
 * 
 * FIXME: should be a whitelisting filter instead of a blacklisting one
 */
public class PNGFilter implements ContentDataFilter {

	private final boolean deleteText;
	private final boolean deleteTimestamp;
	private final boolean checkCRCs;
	static final byte[] pngHeader =
		{(byte) 137, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10};

	PNGFilter(boolean deleteText, boolean deleteTimestamp, boolean checkCRCs) {
		this.deleteText = deleteText;
		this.deleteTimestamp = deleteTimestamp;
		this.checkCRCs = checkCRCs;
	}

	public Bucket readFilter(Bucket data, BucketFactory bf, String charset,
		HashMap otherParams, FilterCallback cb) throws DataFilterException,
		IOException {
		Bucket output = readFilter(data, bf, charset, otherParams, cb, deleteText, deleteTimestamp, checkCRCs, null);
		if(output != null)
			return output;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Need to modify PNG...");
		Bucket filtered = bf.makeBucket(data.size());
		OutputStream os = new BufferedOutputStream(filtered.getOutputStream());
		try {
			readFilter(data, bf, charset, otherParams, cb, deleteText, deleteTimestamp, checkCRCs, os);
			os.flush();
			os.close();
			os = null;
		} finally {
			Closer.close(os);
		}
		return filtered;
	}

	public Bucket readFilter(Bucket data, BucketFactory bf, String charset,
		HashMap otherParams, FilterCallback cb, boolean deleteText, boolean deleteTimestamp, boolean checkCRCs, OutputStream output) throws DataFilterException,
		IOException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		boolean logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		InputStream is = null;
		BufferedInputStream bis = null;
		DataInputStream dis = null;
		try {
			is = data.getInputStream();
			bis = new BufferedInputStream(is);
			dis = new DataInputStream(bis);
			// Check the header
			byte[] headerCheck = new byte[pngHeader.length];
			dis.read(headerCheck);
			if(!Arrays.equals(headerCheck, pngHeader)) {
				// Throw an exception
				String message = l10n("invalidHeader");
				String title = l10n("invalidHeaderTitle");
				throw new DataFilterException(title, title,
					"<p>" + message + "</p>", new HTMLNode("p").addChild("#", message));
			}

			ByteArrayOutputStream baos = null;
			DataOutputStream dos = null;
			if(output != null) {
				baos = new ByteArrayOutputStream();
				dos = new DataOutputStream(baos);
				output.write(pngHeader);
				if(logMINOR)
					Logger.minor(this, "Writing the PNG header to the output bucket");
			}

			// Check the chunks :
			// @see http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html#C.Summary-of-standard-chunks
			boolean finished = false;
			boolean hasSeenIHDR = false;
			boolean hasSeenIEND = false;
			boolean hasSeenPLTE = false;
			boolean hasSeenIDAT = false;
			String lastChunkType = "";
			
			while(!finished) {
				boolean skip = false;
				if(baos != null)
					baos.reset();
				String chunkTypeString = null;
				// Length of the chunk
				byte[] lengthBytes = new byte[4];
				if(dis.read(lengthBytes) < 4)
					throw new IOException("The length of the chunk is invalid!");
				
				int length = ((lengthBytes[0] & 0xff) << 24) + ((lengthBytes[1] & 0xff) << 16) + ((lengthBytes[2] & 0xff) << 8) + (lengthBytes[3] & 0xff);
				if(logMINOR)
					Logger.minor(this, "length " + length);
				if(dos != null)
					dos.write(lengthBytes);

				// Type of the chunk : Should match [a-zA-Z]{4}
				if(dis.read(lengthBytes) < 4)
					throw new IOException("The name of the chunk is invalid!");
				StringBuffer sb = new StringBuffer();
				byte[] chunkTypeBytes = new byte[4];
				for(int i = 0; i < 4; i++) {
					char val = (char) lengthBytes[i];
					if((val >= 65 && val <= 99) || (val >= 97 && val <= 122)) {
						chunkTypeBytes[i] = lengthBytes[i];
						sb.append(val);
					} else
						throw new IOException("The name of the chunk is invalid!");
				}
				if(dos != null)
					dos.write(chunkTypeBytes);
				chunkTypeString = sb.toString();
				if(logMINOR)
					Logger.minor(this, "name " + chunkTypeString);

				// Content of the chunk
				byte[] chunkData = new byte[length];
				int readLength = dis.read(chunkData, 0, length);
				if(readLength < length)
					throw new IOException("The data in the chunk '" + chunkTypeString + "' is " + readLength + " but should be " + length);
				if(logMINOR)
					if(logDEBUG)
						Logger.minor(this, "data " + (chunkData.length == 0 ? "null" : HexUtil.bytesToHex(chunkData)));
					else
						Logger.minor(this, "data " + chunkData.length);
				if(dos != null)
					dos.write(chunkData);

				// CRC of the chunk
				if(dis.read(lengthBytes) < 4)
					throw new IOException("The length of the CRC is invalid!");
				if(dos != null)
					dos.write(lengthBytes);

				if(checkCRCs) {
					long readCRC = (((lengthBytes[0] & 0xff) << 24) + ((lengthBytes[1] & 0xff) << 16) + ((lengthBytes[2] & 0xff) << 8) + (lengthBytes[3] & 0xff)) & 0x00000000ffffffffL;
					CRC32 crc = new CRC32();
					crc.update(chunkTypeBytes);
					crc.update(chunkData);
					long computedCRC = crc.getValue();
					
					if(readCRC != computedCRC) {
						skip = true;
						if(logMINOR)
							Logger.minor(this, "CRC of the chunk " + chunkTypeString + " doesn't match (" + Long.toHexString(readCRC) + " but should be " + Long.toHexString(computedCRC) + ")!");
					}
				}

				if(!skip && "IHDR".equals(chunkTypeString)) {
					if(hasSeenIHDR)
						throw new IOException("Two IHDR chunks detected!!");
					hasSeenIHDR = true;
				}

				if(!hasSeenIHDR)
					throw new IOException("No IHDR chunk!");

				if(!skip && "IEND".equals(chunkTypeString)) {
					if(hasSeenIEND)
						throw new IOException("Two IEND chunks detected!!");
					hasSeenIEND = true;
				}
				
				if(!skip && "PLTE".equalsIgnoreCase(chunkTypeString)) {
					if(hasSeenIDAT)
						throw new IOException("PLTE must be before IDAT");
					hasSeenPLTE = true;
				}
				
				if(!skip && "IDAT".equalsIgnoreCase(chunkTypeString)) {
					if(hasSeenIDAT && !"IDAT".equalsIgnoreCase(lastChunkType))
						throw new IOException("Multiple IDAT chunks must be consecutive!");
					hasSeenIDAT = true;
				}

				if(dis.available() < 1) {
					if(!(hasSeenIEND && hasSeenIHDR))
						throw new IOException("Missing IEND or IHDR!");
					finished = true;
				}

				if(deleteText && "text".equalsIgnoreCase(chunkTypeString))
					skip = true;
				else if(deleteTimestamp && "time".equalsIgnoreCase(chunkTypeString))
					skip = true;
				
				if(skip && output == null)
					return null;
				else if(!skip && output != null) {
					if(logMINOR)
						Logger.minor(this, "Writing " + chunkTypeString + " (" + baos.size() + ") to the output bucket");
					baos.writeTo(output);
					baos.flush();
				}
				lastChunkType = chunkTypeString;
			}
			if(finished && dis.available() > 0)
				throw new IOException("IEND not last chunk");
			
			dis.close();
		} finally {
			Closer.close(dis);
			Closer.close(bis);
			Closer.close(is);
		}
		return data;
	}

	private String l10n(String key) {
		return L10n.getString("PNGFilter." + key);
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset,
		HashMap otherParams, FilterCallback cb) throws DataFilterException,
		IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public static void main(String arg[]) {
		final File fin = new File("/tmp/test.png");
		final File fout = new File("/tmp/test2.png");
		fout.delete();
		final Bucket data = new FileBucket(fin, true, false, false, false, false);
		final Bucket out = new FileBucket(fout, false, true, false, false, false);
		try {
			Logger.setupStdoutLogging(Logger.MINOR, "");
			ContentFilter.FilterOutput output = ContentFilter.filter(data, new ArrayBucketFactory(), "image/png", new URI("http://127.0.0.1:8888/"), null);
			BucketTools.copy(output.data, out);
		} catch(IOException e) {
			System.out.println("Bucket error?: " + e.getMessage());
		} catch(URISyntaxException e) {
			System.out.println("Internal error: " + e.getMessage());
		} catch(InvalidThresholdException e) {
		} finally {
			data.free();
		}
	}
}
