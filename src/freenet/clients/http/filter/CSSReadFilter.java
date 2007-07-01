/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashMap;

import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.NullWriter;

public class CSSReadFilter implements ContentDataFilter, CharsetExtractor {

	public Bucket readFilter(Bucket bucket, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
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
		Bucket temp = bf.makeBucket(bucket.size());
		OutputStream os = temp.getOutputStream();
		Reader r;
		Writer w;
		try {
			r = new BufferedReader(new InputStreamReader(strm, charset), 32768);
			w = new BufferedWriter(new OutputStreamWriter(os, charset), 32768);
		} catch (UnsupportedEncodingException e) {
			os.close();
			strm.close();
			throw UnknownCharsetException.create(e, charset);
		}
		CSSParser parser = new CSSParser(r, w, false, cb);
		parser.parse();
		r.close();
		w.close();
		return temp;
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset,
			HashMap otherParams, FilterCallback cb) throws DataFilterException,
			IOException {
		throw new UnsupportedOperationException();
	}

	public String getCharset(Bucket bucket, String parseCharset) throws DataFilterException, IOException {
		InputStream strm = bucket.getInputStream();
		Writer w = new NullWriter();
		Reader r;
		r = new BufferedReader(new InputStreamReader(strm, parseCharset), 32768);
		CSSParser parser = new CSSParser(r, w, false, new NullFilterCallback());
		try {
			parser.parse();
		} catch (Throwable t) {
			// Ignore ALL errors!
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Caught "+t+" trying to detect MIME type with "+parseCharset);
		}
		r.close();
		return parser.detectedCharset;
	}

}
