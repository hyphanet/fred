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
import freenet.support.io.Closer;
import freenet.support.io.NullWriter;

public class CSSReadFilter implements ContentDataFilter {

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
			parser.filterCSS();
			r.close();
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

	

}
