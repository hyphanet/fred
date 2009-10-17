/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashMap;

import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.NullWriter;

public class CSSReadFilter implements ContentDataFilter, CharsetExtractor {

	public Bucket readFilter(Bucket bucket, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		if (Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(
				this,
				"running "
					+ this
					+ " on "
					+ bucket
					+ ','
                        + charset);
		InputStream strm = bucket.getInputStream();
		Bucket temp = bf.makeBucket(-1);
		OutputStream os = temp.getOutputStream();
		Reader r = null;
		Writer w = null;
		InputStreamReader isr = null;
		OutputStreamWriter osw = null;
		try {
			try {
				isr = new InputStreamReader(strm, charset);
				osw = new OutputStreamWriter(os, charset);
				r = new BufferedReader(isr, 32768);
				w = new BufferedWriter(osw, 32768);
			} catch(UnsupportedEncodingException e) {
				Closer.close(osw);
				Closer.close(os);
				throw UnknownCharsetException.create(e, charset);
			}
			CSSParser parser = new CSSParser(r, w, false, cb, charset, false);
			parser.parse();
			r.close();
			r = null;
			w.close();
			w = null;
		}
		finally {
			Closer.close(strm);
			Closer.close(isr);
			Closer.close(r);
			Closer.close(w);
		}
		return temp;
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		throw new UnsupportedOperationException();
	}

	public String getCharset(Bucket data, String charset) throws DataFilterException, IOException {
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Fetching charset for CSS with initial charset "+charset);
		InputStream strm = data.getInputStream();
		NullWriter w = new NullWriter();
		InputStreamReader isr;
		BufferedReader r = null;
		try {
			try {
				isr = new InputStreamReader(strm, charset);
				r = new BufferedReader(isr, 32768);
			} catch(UnsupportedEncodingException e) {
				throw UnknownCharsetException.create(e, charset);
			}
			CSSParser parser = new CSSParser(r, w, false, new NullFilterCallback(), null, true);
			parser.parse();
			r.close();
			r = null;
			return parser.detectedCharset();
		}
		finally {
			Closer.close(strm);
			Closer.close(r);
			Closer.close(w);
		}
	}

	// CSS 2.1 section 4.4.
	// In all cases these will be confirmed by calling getCharset().
	// We do not use all of the BOMs suggested.
	// Also, we do not use true BOMs.
	
	// We do check for ascii, even though it's the first one to check for anyway, because of the "as specified" rule: if it starts with @charset in ascii, it MUST have a valid charset, or we ignore the whole sheet, as per the spec.
	static final byte[] ascii = parse("40 63 68 61 72 73 65 74 20 22");
	static final byte[] utf16be = parse("00 40 00 63 00 68 00 61 00 72 00 73 00 65 00 74 00 20 00 22");
	static final byte[] utf16le = parse("40 00 63 00 68 00 61 00 72 00 73 00 65 00 74 00 20 00 22 00");
	static final byte[] utf32_le = parse("40 00 00 00 63 00 00 00 68 00 00 00 61 00 00 00 72 00 00 00 73 00 00 00 65 00 00 00 74 00 00 00 20 00 00 00 22 00 00 00");
	static final byte[] utf32_be = parse("00 00 00 40 00 00 00 63 00 00 00 68 00 00 00 61 00 00 00 72 00 00 00 73 00 00 00 65 00 00 00 74 00 00 00 20 00 00 00 22");
	static final byte[] ebcdic = parse("7C 83 88 81 99 A2 85 A3 40 7F");
	static final byte[] ibm1026 = parse("AE 83 88 81 99 A2 85 A3 40 FC");

	// Not supported.
	static final byte[] utf32_2143 = parse("00 00 40 00 00 00 63 00 00 00 68 00 00 00 61 00 00 00 72 00 00 00 73 00 00 00 65 00 00 00 74 00 00 00 20 00 00 00 22 00");
	static final byte[] utf32_3412 = parse("00 40 00 00 00 63 00 00 00 68 00 00 00 61 00 00 00 72 00 00 00 73 00 00 00 65 00 00 00 74 00 00 00 20 00 00 00 22 00 00");
	static final byte[] gsm = parse("00 63 68 61 72 73 65 74 20 22");
	
	static final int maxBOMLength = Math.max(utf16be.length, Math.max(utf16le.length, Math.max(utf32_le.length, Math.max(utf32_be.length, Math.max(ebcdic.length, Math.max(ibm1026.length, Math.max(utf32_2143.length, Math.max(utf32_3412.length, gsm.length))))))));
	
	static final byte[] parse(String s) {
		s = s.replaceAll(" ", "");
		return HexUtil.hexToBytes(s);
	}
	
	public BOMDetection getCharsetByBOM(Bucket bucket) throws DataFilterException, IOException {
		
		InputStream is = null;
		try {
			byte[] data = new byte[maxBOMLength];
			is = new BufferedInputStream(bucket.getInputStream());
			int read = 0;
			while(read < data.length) {
				int x;
				try {
					x = is.read(data, read, data.length - read);
				} catch(EOFException e) {
					x = -1;
				}
				if(x <= 0)
					break;
			}
			is.close();
			is = null;
			if(ContentFilter.startsWith(data, ascii))
				return new BOMDetection("UTF-8", true);
			if(ContentFilter.startsWith(data, utf16be))
				return new BOMDetection("UTF-16BE", true);
			if(ContentFilter.startsWith(data, utf16le))
				return new BOMDetection("UTF-16LE", true);
			if(ContentFilter.startsWith(data, utf32_be))
				return new BOMDetection("UTF-32BE", true);
			if(ContentFilter.startsWith(data, utf32_le))
				return new BOMDetection("UTF-32LE", true);
			if(ContentFilter.startsWith(data, ebcdic))
				return new BOMDetection("IBM01140", true);
			if(ContentFilter.startsWith(data, ibm1026))
				return new BOMDetection("IBM1026", true);
			
			// Unsupported BOMs
			
			if(ContentFilter.startsWith(data, utf32_2143))
				throw new UnsupportedCharsetInFilterException("UTF-32-2143");
			if(ContentFilter.startsWith(data, utf32_3412))
				throw new UnsupportedCharsetInFilterException("UTF-32-3412");
			if(ContentFilter.startsWith(data, gsm))
				throw new UnsupportedCharsetInFilterException("GSM 03.38");
		} finally {
			Closer.close(is);
		}
		return null;
	}

}
