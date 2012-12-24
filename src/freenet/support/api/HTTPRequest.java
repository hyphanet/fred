/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.api;

import java.util.Collection;
import java.util.NoSuchElementException;

import javax.naming.SizeLimitExceededException;

/** A parsed HTTP request (GET or POST). Request parameters are parameters
 * encoded into the URI, or part of a POST form which is encoded as 
 * application/x-www-form-urlencoded. Parts are parameters (including files)
 * uploaded in a POST in multipart/form-data. Use of the former encoding for
 * POSTs is strongly discouraged as it has character set issues. We do 
 * support methods other than GET and POST for e.g. WebDAV, but they are not
 * common. */
public interface HTTPRequest {

	/**
	 * The path of this request, where the part of the path the specified the
	 * plugin has already been removed..
	 */
	public String getPath();

	/**
	 * 
	 * @return false if the query string was totally empty
	 */
	public boolean hasParameters();

	/**
	 * Check if a parameter was set in the request at all, either with or
	 * without a value.
	 * 
	 * @param name
	 *            the name of the parameter to check
	 * @return true if the parameter was set in the request, not regarding if
	 *         the value is empty
	 */
	public boolean isParameterSet(String name);

	/**
	 * Get the value of a request parameter, using an empty string as default
	 * value if the parameter was not set. This method will never return null,
	 * so its safe to do things like
	 * 
	 * <p>
	 * <code>
	 *   if (request.getParam(&quot;abc&quot;).equals(&quot;def&quot;))
	 * </code>
	 * </p>
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return the parameter value as String, or an empty String if the value
	 *         was missing or empty
	 */
	public String getParam(String name);

	/**
	 * Get the value of a request parameter, using the specified default value
	 * if the parameter was not set or has an empty value.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @param defaultValue
	 *            the default value to be returned if the parameter is missing
	 *            or empty
	 * @return either the parameter value as String, or the default value
	 */
	public String getParam(String name, String defaultValue);

	/**
	 * Get the value of a request parameter converted to an int, using 0 as
	 * default value. If there are multiple values for this parameter, the first
	 * value is used.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return either the parameter value as int, or 0 if the parameter is
	 *         missing, empty or invalid
	 */
	public int getIntParam(String name);

	/**
	 * Get the value of a request parameter converted to an <code>int</code>,
	 * using the specified default value. If there are multiple values for this
	 * parameter, the first value is used.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @param defaultValue
	 *            the default value to be returned if the parameter is missing,
	 *            empty or invalid
	 * @return either the parameter value as int, or the default value
	 */
	public int getIntParam(String name, int defaultValue);

	/** Get a part as an integer with a default value if it is not set. */
	public int getIntPart(String name, int defaultValue);

	/**
	 * Get all values of a request parameter as a string array. If the parameter
	 * was not set at all, an empty array is returned, so this method will never
	 * return <code>null</code>.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return an array of all parameter values that might include empty values
	 */
	public String[] getMultipleParam(String name);

	/**
	 * Get all values of a request parameter as int array, ignoring all values
	 * that can not be parsed. If the parameter was not set at all, an empty
	 * array is returned, so this method will never return <code>null</code>.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return an int array of all parameter values that could be parsed as int
	 */
	public int[] getMultipleIntParam(String name);

	/** Get a file uploaded in the HTTP request. */
	public HTTPUploadedFile getUploadedFile(String name);

	/** Get a part as a Bucket. Parts can be very large, as they are POST
	 * data from multipart/form-data and can include uploaded files. */
	public Bucket getPart(String name);

	/** Is a part set with the given name? */
	public boolean isPartSet(String name);

	/**
	 * Get a request part as a String. Parts are passed in through attached
	 * data in a POST in multipart/form-data encoding; parameters are 
	 * passed in through the URI.
	 * Returns an empty String if the length limit is exceeded and therefore is deprecated.
	 */
	@Deprecated
	public String getPartAsString(String name, int maxlength);
	
	public String getPartAsStringThrowing(String name, int maxlength) throws NoSuchElementException, SizeLimitExceededException;
	
	/**
	 * Gets up to maxLength characters from the part, ignores any characters after the limit.
	 * If no such part exists, an empty String is returned.
	 */
	public String getPartAsStringFailsafe(String name, int maxlength);

	/**
	 * Get a request part as bytes.
	 * Returns an empty array if the length limit is exceeded and therefore is deprecated.
	 */
	@Deprecated
	public byte[] getPartAsBytes(String name, int maxlength);
	
	public byte[] getPartAsBytesThrowing(String name, int maxlength) throws NoSuchElementException, SizeLimitExceededException;
	
	/**
	 * Gets up to maxLength bytes from the part, ignores any bytes after the limit.
	 * If no such part exists, a byte[] with size 0 is returned. 
	 */
	public byte[] getPartAsBytesFailsafe(String name, int maxlength);
	

	/** Free all the parts. They may be stored on disk so it is important
	 * that this be called at some point. */
	public void freeParts();

	/** Get a part as a long, with a default value if it is not set. */
	public long getLongParam(String name, long defaultValue);

	/** Get the HTTP method, typically GET or POST. */
	public String getMethod();

	/** Get the original uploaded raw data for a POST. */
	public Bucket getRawData();
	
	/** Get the value of a specific header on the request. */
	public String getHeader(String name);

	/** Get the length of the original uploaded raw data for a POST. */
	public int getContentLength();

	public String[] getParts();

	/**
	 * Returns the names of all parameters.
	 *
	 * @return The names of all parameters
	 */
	public Collection<String> getParameterNames();

	/** Is the incognito=true boolean set? Sadly this does not prove that
	 * it is actually running incognito mode... */
	public boolean isIncognito();

	/** Is the browser Chrome according to User-Agent? */
	public boolean isChrome();

}
