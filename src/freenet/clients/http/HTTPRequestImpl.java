/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.Map.Entry;

import javax.naming.SizeLimitExceededException;

import freenet.support.Fields;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.SimpleReadOnlyArrayBucket;
import freenet.support.URLEncoder;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.api.HTTPUploadedFile;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;
import freenet.support.io.LineReadingInputStream;

/**
 * Used for passing all HTTP request information to the FredPlugin that handles
 * the request. It parses the query string and has several methods for accessing
 * the request parameter values.
 * 
 * @author nacktschneck
 */
public class HTTPRequestImpl implements HTTPRequest {

	/**
	 * This map is used to store all parameter values.
	 *  
	 * Don't access this map directly, use {@link #getParameterValueList(String)} and
	 * {@link #isParameterSet(String)} instead
	 */
	private final Map<String, List<String>> parameterNameValuesMap = new HashMap<String, List<String>>();

	/**
	 * the original URI as given to the constructor
	 */
	private URI uri;
	
	/**
	 * The headers sent by the client
	 */
	private MultiValueTable<String, String> headers;
	
	/**
	 * The data sent in the connection
	 */
	private Bucket data;
	
	/**
	 * A hashmap of buckets that we use to store all the parts for a multipart/form-data request
	 */
	private HashMap<String, Bucket> parts;
	
	private boolean freedParts;
	
	/** A map for uploaded files. */
	private Map<String, HTTPUploadedFileImpl> uploadedFiles = new HashMap<String, HTTPUploadedFileImpl>();
	
	private final BucketFactory bucketfactory;
	
	private final String method;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * Create a new HTTPRequest for the given URI and parse its request
	 * parameters.
	 * 
	 * @param uri
	 *            the URI being requested
	 */
	public HTTPRequestImpl(URI uri, String method) {
		this.uri = uri;
		this.parseRequestParameters(uri.getRawQuery(), true, false);
		this.data = null;
		this.parts = null;
		this.bucketfactory = null;
		this.method = method;
	}

	/**
	 * Creates a new HTTPRequest for the given path and url-encoded query string
	 * 
	 * @param path i.e. /test/test.html
	 * @param encodedQueryString a=some+text&b=abc%40def.de
	 * @throws URISyntaxException if the URI is invalid
	 */
	public HTTPRequestImpl(String path, String encodedQueryString, String method) throws URISyntaxException {
		this.data = null;
		this.parts = null;
		this.bucketfactory = null;
		if ((encodedQueryString!=null) && (encodedQueryString.length()>0)) {
			this.uri = new URI(path+ '?' +encodedQueryString);
		} else {
			this.uri = new URI(path);
		}
		this.method = method;
		this.parseRequestParameters(uri.getRawQuery(), true, false);
	}
	
	/**
	 * Creates a new HTTPRequest for the given URI and data.
	 * 
	 * @param uri The URI being requested
	 * @param h Client headers
	 * @param d The data
	 * @param ctx The toadlet context (for headers and bucket factory)
	 * @throws URISyntaxException if the URI is invalid
	 */
	public HTTPRequestImpl(URI uri, Bucket d, ToadletContext ctx, String method) {
		this.uri = uri;
		this.headers = ctx.getHeaders();
		this.parseRequestParameters(uri.getRawQuery(), true, false);
		this.data = d;
		this.parts = new HashMap<String, Bucket>();
		this.bucketfactory = ctx.getBucketFactory();
		this.method = method;
		if(data != null) {
			try {
				this.parseMultiPartData();
			} catch (IOException ioe) {
				Logger.error(this, "Temporary files error ? Could not parse: "+ioe, ioe);
			}
		}
	}
	

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getPath()
	 */
	@Override
	public String getPath() {
		return this.uri.getPath();
	}


	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#hasParameters()
	 */
	@Override
	public boolean hasParameters() {
		return ! this.parameterNameValuesMap.isEmpty();
	}

	/**
	 * Returns the names of all parameters.
	 *
	 * @return The names of all parameters
	 */
	@Override
	public Collection<String> getParameterNames() {
		return parameterNameValuesMap.keySet();
	}

	/**
	 * Parse the query string and populate {@link #parameterNameValuesMap} with
	 * the lists of values for each parameter. If this method is not called at
	 * all, all other methods would be useless. Because they rely on the
	 * parameter map to be filled.
	 * 
	 * @param queryString
	 *            the query string in its raw form (not yet url-decoded)
	 * @param doUrlDecoding TODO
	 */
	private void parseRequestParameters(String queryString, boolean doUrlDecoding, boolean asParts) {

		if(logMINOR) Logger.minor(this, "queryString is "+queryString+", doUrlDecoding="+doUrlDecoding);

		Map<String, List<String>> parameters = parseUriParameters(queryString, doUrlDecoding);

		if (asParts) {
			for (Entry<String, List<String>> parameterValues : parameters.entrySet()) {
				List<String> values = parameterValues.getValue();
				String value = values.get(values.size() - 1);
				byte[] buf;
				try {
					buf = value.getBytes("UTF-8");
				} catch (UnsupportedEncodingException e) {
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				} // FIXME some other encoding?
				Bucket b = new SimpleReadOnlyArrayBucket(buf);
				parts.put(parameterValues.getKey(), b);
				if(logMINOR)
					Logger.minor(this, "Added as part: name="+parameterValues.getKey()+" value="+value);
			}
		} else {
			parameterNameValuesMap.clear();
			parameterNameValuesMap.putAll(parameters);
		}
	}

	/**
	 * Get the first value of the parameter with the given name.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return the first value or <code>null</code> if the parameter was not
	 *         set
	 */
	private String getParameterValue(String name) {
		if (!this.isParameterSet(name)) {
			return null;
		}
		List<String> allValues = this.getParameterValueList(name);
		return allValues.get(0);
	}

	/**
	 * Get the list of all values for the parameter with the given name. When
	 * this method is called for a given parameter for the first time, a new
	 * empty list of values is created and stored in
	 * {@link #parameterNameValuesMap}. This list is returned and should be
	 * used to add parameter values. If you only want to check if a parameter is
	 * set at all, you must use {@link #isParameterSet(String)}.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return the list of all values for this parameter that were parsed so
	 *         far.
	 */
	private List<String> getParameterValueList(String name) {
		List<String> values = this.parameterNameValuesMap.get(name);
		if (values == null) {
			values = new LinkedList<String>();
			this.parameterNameValuesMap.put(name, values);
		}
		return values;
	}

	/**
	 * Parses the parameters from the given query string, optionally decoding
	 * the parameters using UTF-8.
	 *
	 * @param queryString
	 *            The query string to decode
	 * @param doUrlDecoding
	 *            {@code true} to decode the parameter names and values
	 * @return The decoded parameters
	 */
	public static Map<String, List<String>> parseUriParameters(String queryString, boolean doUrlDecoding) {
		if(logMINOR) Logger.minor(HTTPRequestImpl.class, "queryString is "+queryString+", doUrlDecoding="+doUrlDecoding);

		/* create result map. */
		Map<String, List<String>> parameters = new HashMap<String, List<String>>();

		// nothing to do if there was no query string in the URI
		if ((queryString == null) || (queryString.length() == 0)) {
			return parameters;
		}

		// iterate over all tokens in the query string (separated by &)
		StringTokenizer tokenizer = new StringTokenizer(queryString, "&");
		while (tokenizer.hasMoreTokens()) {
			String nameValueToken = tokenizer.nextToken();

			if(logMINOR) Logger.minor(HTTPRequestImpl.class, "Token: "+nameValueToken);

			// a token can be either a name, or a name value pair...
			String name = null;
			String value = "";
			int indexOfEqualsChar = nameValueToken.indexOf('=');
			if (indexOfEqualsChar < 0) {
				// ...it's only a name, so the value stays emptys
				name = nameValueToken;
				if(logMINOR) Logger.minor(HTTPRequestImpl.class, "Name: "+name);
			} else if (indexOfEqualsChar == nameValueToken.length() - 1) {
				// ...it's a name with an empty value, so remove the '='
				// character
				name = nameValueToken.substring(0, indexOfEqualsChar);
				if(logMINOR) Logger.minor(HTTPRequestImpl.class, "Name: "+name);
			} else {
				// ...it's a name value pair, split into name and value
				name = nameValueToken.substring(0, indexOfEqualsChar);
				value = nameValueToken.substring(indexOfEqualsChar + 1);
				if(logMINOR) Logger.minor(HTTPRequestImpl.class, "Name: "+name+" Value: "+value);
			}

			// url-decode the name and value
			if (doUrlDecoding) {
					try {
						name = URLDecoder.decode(name, "UTF-8");
						value = URLDecoder.decode(value, "UTF-8");
					} catch (UnsupportedEncodingException e) {
						throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
					}
				if(logMINOR) {
					Logger.minor(HTTPRequestImpl.class, "Decoded name: "+name);
					Logger.minor(HTTPRequestImpl.class, "Decoded value: "+value);
				}
			}

			List<String> values = parameters.get(name);
			if (values == null) {
				values = new ArrayList<String>();
				parameters.put(name, values);
			}
			values.add(value);
		}

		return parameters;
	}

	/**
	 * Creates a query string from the given parameters.
	 *
	 * @param parameterValues
	 *            The parameters to create a query string from
	 * @param doUrlEncoding
	 *            {@code true} if encoding for HTTP headers, {@code false} to
	 *            only encode unsafe characters
	 * @return The query string
	 */
	public static String createQueryString(Map<String, List<String>> parameterValues, boolean doUrlEncoding) {
		StringBuilder queryString = new StringBuilder();
		for (Entry<String, List<String>> parameter : parameterValues.entrySet()) {
			for (String value : parameter.getValue()) {
				if (queryString.length() > 0) {
					queryString.append('&');
				}
				queryString.append(URLEncoder.encode(parameter.getKey(), doUrlEncoding));
				queryString.append('=');
				queryString.append(URLEncoder.encode(value, doUrlEncoding));
			}
		}
		return queryString.toString();
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#isParameterSet(java.lang.String)
	 */
	@Override
	public boolean isParameterSet(String name) {
		return this.parameterNameValuesMap.containsKey(name);
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getParam(java.lang.String)
	 */
	@Override
	public String getParam(String name) {
		return this.getParam(name, "");
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getParam(java.lang.String, java.lang.String)
	 */
	@Override
	public String getParam(String name, String defaultValue) {
		String value = this.getParameterValue(name);
		if (value == null || value.isEmpty()) {
			return defaultValue;
		}
		return value;
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getIntParam(java.lang.String)
	 */
	@Override
	public int getIntParam(String name) {
		return this.getIntParam(name, 0);
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getIntParam(java.lang.String, int)
	 */
	@Override
	public int getIntParam(String name, int defaultValue) {
		if (!this.isParameterSet(name)) {
			return defaultValue;
		}
		String value = this.getParameterValue(name);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getIntPart(java.lang.String, int)
	 */
	@Override
	public int getIntPart(String name, int defaultValue) {
		if (!this.isPartSet(name)) {
			return defaultValue;
		}
		String value = this.getPartAsString(name, 32);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	// TODO: add similar methods for long, boolean etc.

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getMultipleParam(java.lang.String)
	 */
	@Override
	public String[] getMultipleParam(String name) {
		List<String> valueList = this.getParameterValueList(name);
		String[] values = new String[valueList.size()];
		valueList.toArray(values);
		return values;
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getMultipleIntParam(java.lang.String)
	 */
	@Override
	public int[] getMultipleIntParam(String name) {
		List<String> valueList = this.getParameterValueList(name);

		// try parsing all values and put the valid Integers in a new list
		List<Integer> intValueList = new ArrayList<Integer>();
		for (int i = 0; i < valueList.size(); i++) {
			try {
				intValueList.add(Integer.valueOf(valueList.get(i)));
			} catch (Exception e) {
				// ignore invalid parameter values
			}
		}

		// convert the valid Integers to an array of ints
		int[] values = new int[intValueList.size()];
		for (int i = 0; i < intValueList.size(); i++) {
			values[i] = intValueList.get(i);
		}
		return values;
	}


	// TODO: add similar methods for multiple long, boolean etc.
	
	
	/**
	 * Parse submitted data from a bucket.
	 * Note that if this is application/x-www-form-urlencoded, it will come out as
	 * params, whereas if it is multipart/form-data it will be separated into buckets.
	 */
	private void parseMultiPartData() throws IOException {
		InputStream is = null;
		BufferedInputStream bis = null;
		LineReadingInputStream lis = null;
		OutputStream bucketos = null;
		OutputStream bbos = null;

		try {
			if(data == null)
				return;
			String ctype = this.headers.get("content-type");
			if(ctype == null)
				return;
			if(logMINOR)
				Logger.minor(this, "Uploaded content-type: " + ctype);
			String[] ctypeparts = ctype.split(";");
			if(ctypeparts[0].equalsIgnoreCase("application/x-www-form-urlencoded")) {
				// Completely different encoding, but easy to handle
				if(data.size() > 1024 * 1024)
					throw new IOException("Too big");
				byte[] buf = BucketTools.toByteArray(data);
				String s = new String(buf, "us-ascii");
				parseRequestParameters(s, true, true);
			}
			if(!ctypeparts[0].trim().equalsIgnoreCase("multipart/form-data") || (ctypeparts.length < 2))
				return;

			String boundary = null;
			for(String ctypepart: ctypeparts) {
				String[] subparts = ctypepart.split("=");
				if((subparts.length == 2) && subparts[0].trim().equalsIgnoreCase("boundary"))
					boundary = subparts[1];
			}

			if((boundary == null) || (boundary.length() == 0))
				return;
			if(boundary.charAt(0) == '"')
				boundary = boundary.substring(1);
			if(boundary.charAt(boundary.length() - 1) == '"')
				boundary = boundary.substring(0, boundary.length() - 1);

			boundary = "--" + boundary;

			if(logMINOR)
				Logger.minor(this, "Boundary is: " + boundary);

			is = this.data.getInputStream();
			bis = new BufferedInputStream(is, 32768);
			lis = new LineReadingInputStream(bis);

			String line;
			line = lis.readLine(100, 100, false); // really it's US-ASCII, but ISO-8859-1 is close enough.
			while((bis.available() > 0) && !line.equals(boundary)) {
				line = lis.readLine(100, 100, false);
			}

			boundary = "\r\n" + boundary;

			Bucket filedata = null;
			String name = null;
			String filename = null;
			String contentType = null;

			while(bis.available() > 0) {
				name = null;
				filename = null;
				contentType = null;
				// chomp headers
				while((line = lis.readLine(200, 200, true)) /* should be UTF-8 as we told the browser to send UTF-8 */ != null) {
					if(line.length() == 0)
						break;

					String[] lineparts = line.split(":");
					if(lineparts == null || lineparts.length == 0)
						continue;
					String hdrname = lineparts[0].trim();

					if(hdrname.equalsIgnoreCase("Content-Disposition")) {
						if(lineparts.length < 2)
							continue;
						String[] valueparts = lineparts[1].split(";");

						for(int i = 0; i < valueparts.length; i++) {
							String[] subparts = valueparts[i].split("=");
							if(subparts.length != 2)
								continue;
							String fieldname = subparts[0].trim();
							String value = subparts[1].trim();
							if(value.startsWith("\"") && value.endsWith("\""))
								value = value.substring(1, value.length() - 1);
							if(fieldname.equalsIgnoreCase("name"))
								name = value;
							else if(fieldname.equalsIgnoreCase("filename"))
								filename = value;
						}
					}
					else if(hdrname.equalsIgnoreCase("Content-Type")) {
						contentType = lineparts[1].trim();
						if(logMINOR)
							Logger.minor(this, "Parsed type: " + contentType);
					}
					else {
					// Do nothing, irrelevant header
					}
				}

				if(name == null)
					continue;

				// we should be at the data now. Start reading it in, checking for the
			// boundary string

				// we can only give an upper bound for the size of the bucket
				filedata = this.bucketfactory.makeBucket(bis.available());
				bucketos = filedata.getOutputStream();
				bbos = new BufferedOutputStream(bucketos, 32768);
				// buffer characters that match the boundary so far
			// FIXME use whatever charset was used
				byte[] bbound = boundary.getBytes("UTF-8"); // ISO-8859-1? boundary should be in US-ASCII
				int offset = 0;
				while((bis.available() > 0) && (offset < bbound.length)) {
					byte b = (byte) bis.read();

					if(b == bbound[offset])
						offset++;
					else if((b != bbound[offset]) && (offset > 0)) {
						// offset bytes matched, but no more
					// write the bytes that matched, then the non-matching byte
						bbos.write(bbound, 0, offset);
						offset = 0;
						if(b == bbound[0])
							offset = 1;
						else
							bbos.write(b);
					}
					else
						bbos.write(b);
				}

				bbos.close();
				bbos = null; bucketos = null;
			
				parts.put(name, filedata);
				if(logMINOR)
					Logger.minor(this, "Name = " + name + " length = " + filedata.size() + " filename = " + filename);
				if(filename != null)
					uploadedFiles.put(name, new HTTPUploadedFileImpl(filename, contentType, filedata));
			}
		}
		finally {
			Closer.close(bbos);
			Closer.close(bucketos);
			Closer.close(lis);
			Closer.close(bis);
			Closer.close(is);
		}
	}
	
	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getUploadedFile(java.lang.String)
	 */
	@Override
	public HTTPUploadedFile getUploadedFile(String name) {
		return uploadedFiles.get(name);
	}
	
	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getPart(java.lang.String)
	 */
	@Override
	public Bucket getPart(String name) {
		if(freedParts) throw new IllegalStateException("Already freed");
		return this.parts.get(name);
	}
	
	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#isPartSet(java.lang.String)
	 */
	@Override
	public boolean isPartSet(String name) {
		if(freedParts) throw new IllegalStateException("Already freed");
		if(parts == null)
			return false;

		return this.parts.containsKey(name);
	}

	@Override
	@Deprecated
	public String getPartAsString(String name, int maxlength) {
		try {
			return new String(getPartAsBytes(name, maxlength), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
	}
	
	@Override
	public String getPartAsStringThrowing(String name, int maxLength) throws NoSuchElementException, SizeLimitExceededException {
		if(freedParts) throw new IllegalStateException("Already freed");
		Bucket part = this.parts.get(name);
		
		if(part == null)
			throw new NoSuchElementException(name);
		
		if(part.size() > maxLength)
			throw new SizeLimitExceededException();
		
		return getPartAsLimitedString(part, maxLength);
	}
	
	@Override
	public String getPartAsStringFailsafe(String name, int maxLength) {
		if(freedParts) throw new IllegalStateException("Already freed");
		Bucket part = this.parts.get(name);
		return part == null ? "" : getPartAsLimitedString(part, maxLength);
	}
	
	private String getPartAsLimitedString(Bucket part, int maxLength) {
		try {
			return new String(getPartAsLimitedBytes(part, maxLength), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getPartAsString(java.lang.String, int)
	 */
	@Override
	@Deprecated
	public byte[] getPartAsBytes(String name, int maxlength) {
		if(freedParts) throw new IllegalStateException("Already freed");
		Bucket part = this.parts.get(name);
		if(part == null) return new byte[0];
		
		if (part.size() > maxlength) return new byte[0];
		
		InputStream is = null;
		DataInputStream dis = null;
		try {
			is = part.getInputStream();
			dis = new DataInputStream(is);
			byte[] buf = new byte[is.available()];
			dis.readFully(buf);
			return buf;
		} catch (IOException ioe) {
	         Logger.error(this, "Caught IOE:" + ioe.getMessage());
		} finally {
			Closer.close(dis);
			if(dis == null) Closer.close(is); // DataInputStream.close() does this for us normally
		}
		
		return new byte[0];
	}
	
	@Override
	public byte[] getPartAsBytesThrowing(String name, int maxLength) throws NoSuchElementException, SizeLimitExceededException {
		if(freedParts) throw new IllegalStateException("Already freed");
		Bucket part = this.parts.get(name);
		
		if(part == null)
			throw new NoSuchElementException(name);
		
		if(part.size() > maxLength)
			throw new SizeLimitExceededException();
		
		return getPartAsLimitedBytes(part, maxLength);
	}
	
	@Override
	public byte[] getPartAsBytesFailsafe(String name, int maxLength) {
		if(freedParts) throw new IllegalStateException("Already freed");
		Bucket part = this.parts.get(name);
		return part == null ? new byte[0] : getPartAsLimitedBytes(part, maxLength);
	}
	
	private byte[] getPartAsLimitedBytes(Bucket part, int maxLength) {
		InputStream is = null;
		DataInputStream dis = null;
		try {
			is = part.getInputStream();
			dis = new DataInputStream(is);
			byte[] buf = new byte[Math.min(dis.available(), maxLength)];
			dis.readFully(buf, 0, buf.length);
			return buf;
		} catch (IOException ioe) {
	         Logger.error(this, "Caught IOE:" + ioe.getMessage());
	         return new byte[0];
		} finally {
			Closer.close(dis);
			if(dis == null) Closer.close(is); // DataInputStream.close() does this for us normally
		}
	}
	
	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#freeParts()
	 */
	@Override
	public void freeParts() {
		if (this.parts == null) return;
		
		for (Bucket b : this.parts.values()) {
			b.free();
		}
		parts.clear();
		freedParts = true;
		// Do not free data. Caller is responsible for that.
	}

	/* (non-Javadoc)
	 * @see freenet.clients.http.HTTPRequest#getLongParam(java.lang.String, long)
	 */
	@Override
	public long getLongParam(String name, long defaultValue) {
		if (!this.isParameterSet(name)) {
			return defaultValue;
		}
		String value = this.getParameterValue(name);
		try {
			return Fields.parseLong(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Container for uploaded files in HTTP POST requests.
	 * 
	 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
	 * @version $Id$
	 */
	public static class HTTPUploadedFileImpl implements HTTPUploadedFile {

		/** The filename. */
		private final String filename;

		/** The content type. */
		private final String contentType;

		/** The data. */
		private final Bucket data;

		/**
		 * Creates a new file with the specified filename, content type, and
		 * data.
		 * 
		 * @param filename
		 *            The name of the file
		 * @param contentType
		 *            The content type of the file
		 * @param data
		 *            The data of the file
		 */
		public HTTPUploadedFileImpl(String filename, String contentType, Bucket data) {
			this.filename = filename;
			this.contentType = contentType;
			this.data = data;
		}

		/* (non-Javadoc)
		 * @see freenet.clients.http.HTTPUploadedFile#getContentType()
		 */
		@Override
		public String getContentType() {
			return contentType;
		}

		/* (non-Javadoc)
		 * @see freenet.clients.http.HTTPUploadedFile#getData()
		 */
		@Override
		public Bucket getData() {
			return data;
		}

		/* (non-Javadoc)
		 * @see freenet.clients.http.HTTPUploadedFile#getFilename()
		 */
		@Override
		public String getFilename() {
			return filename;
		}

	}

	@Override
	public String getMethod() {
		return method;
	}

	@Override
	public Bucket getRawData() {
		return data;
	}

	@Override
	public String getHeader(String name) {
		assert(name.equals(name.toLowerCase()));
		return this.headers.get(name.toLowerCase());
	}

	@Override
	public int getContentLength() {
		String slen = headers.get("content-length");
		if (slen == null)
			return -1;
		// it is already parsed, so NumberFormatException can not happens here
		return Integer.parseInt(slen);
	}

	@Override
	public String[] getParts() {
		if(freedParts) throw new IllegalStateException("Already freed");
		return parts.keySet().toArray(new String[parts.size()]);
	}

	@Override
	public boolean isIncognito() {
		if(isParameterSet("incognito"))
			return Boolean.valueOf(getParam("incognito"));
		return false;
	}

	@Override
	public boolean isChrome() {
		String ua = getHeader("user-agent");
		if(ua != null) {
			if(ua.contains("Chrome")) return true;
		}
		return false;
	}

}
