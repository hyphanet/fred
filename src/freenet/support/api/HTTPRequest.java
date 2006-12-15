package freenet.support.api;


public interface HTTPRequest {

	/**
	 * The path of this request, where the part of the path the specified the
	 * plugin has already been removed..
	 */
	public String getPath();

	/**
	 * 
	 * @return true if the query string was totally empty
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

	public int getIntPart(String name, int defaultValue);

	/**
	 * Get all values of a request parameter as a string array. If the parameter
	 * was not set at all, an empty array is returned, so this method will never
	 * return <code>null</code>.
	 * 
	 * @param name
	 *            the name of the parameter to get
	 * @return an array of all paramter values that might include empty values
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

	public HTTPUploadedFile getUploadedFile(String name);

	public Bucket getPart(String name);

	public boolean isPartSet(String name);

	public String getPartAsString(String name, int maxlength);

	public void freeParts();

	public long getLongParam(String name, long defaultValue);

}