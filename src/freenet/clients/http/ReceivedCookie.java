/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Hashtable;

import freenet.support.Logger;

/**
 * A cookie which the server has received from the client.
 * 
 * This class is not thread-safe!
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class ReceivedCookie extends Cookie {
	
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(ReceivedCookie.class);
	}
	
	private String notValidatedName;
	
	private Hashtable<String, String> content;
	

	/**
	 * Constructor for creating cookies from parsed key-value pairs.
	 *
	 * Does not validate the names or values of the keys, each attribute is validated at the first call to it's getter method.
	 * Therefore, no CPU time is wasted if the client sends cookies which we do not use.
	 */
	private ReceivedCookie(String myName, Hashtable<String, String> myContent) {
		// We do not validate the input here, we only parse it if someone actually tries to access this cookie.
		notValidatedName = myName;
		content = myContent;
		
		// We do NOT parse the version even though RFC2965 requires it because Firefox (3.0.14) does not give us a version.
		
		version = 1;
		//version = Integer.parseInt(content.get("$version"));
		
		//if(version != 1)
		//	throw new IllegalArgumentException("Invalid version: " + version);
	}
	
	/**
	 * Parses the value of a "Cookie:" HTTP header and returns a list of received cookies which it contained.
	 * - A single "Cookie:" header is allowed to contain multiple cookies. Further, a HTTP request can contain multiple "Cookie" keys!.
	 * 
	 * @param httpHeader The value of a "Cookie:" header (i.e. the prefix "Cookie:" must not be contained in this parameter!) 
	 * @return A list of {@link ReceivedCookie} objects. The validity of their name/value pairs is not deeply checked, their getName() / getValue() might throw!
	 * @throws ParseException If the general formatting of the cookie is wrong.
	 */
	protected static ArrayList<ReceivedCookie> parseHeader(String httpHeader) throws ParseException {

		if(logMINOR)
			Logger.minor(ReceivedCookie.class, "Received HTTP cookie header:" + httpHeader);

		char[] header = httpHeader.toCharArray();
		
		String currentCookieName = null;
		Hashtable<String,String> currentCookieContent = new Hashtable<String, String>(16);
		
		ArrayList<ReceivedCookie> cookies = new ArrayList<ReceivedCookie>(4); // TODO: Adjust to the usual amount of cookies which fred uses + 1
		
		// We do manual parsing instead of using regular expressions for two reasons:
		// 1. The value of cookies can be quoted, therefore it is a context-free language and not a regular language - we cannot express it with a regexp!
		// 2. Its very fast :)
		
		// Set to true if a broken browser (Konqueror) specifies a cookie where the name is NOT the first attribute.
		try {
		for(int i = 0; i < header.length;) {
			// Skip leading whitespace of key, we must do a header.length check because there might be no more key, so we continue;
			if(Character.isWhitespace(header[i])) {
				++i;
				continue;
			}
			
			String key;
			String value = null;
			
			// Parse key
			{
				int keyBeginIndex = i;
	
				while(i < header.length && header[i] != '=' && header[i] != ';')
					++i;
				
				int keyEndIndex = i;
				
				if(keyEndIndex >= header.length || header[keyEndIndex] == ';')
					value = "";
				
				while(Character.isWhitespace(header[keyEndIndex-1])) // Remove trailing whitespace
					--keyEndIndex;
				
				key = new String(header, keyBeginIndex, keyEndIndex - keyBeginIndex).toLowerCase();
				
				if(key.length() == 0)
					throw new ParseException("Invalid cookie: Contains an empty key: " + httpHeader, i);
				
				// We're done parsing the key, continue to the next character.
				++i;
			}
			
			// Parse value (empty values are allowed).
			if(value == null && i < header.length) {
				while(Character.isWhitespace(header[i])) // Skip leading whitespace
					++i;
				
				int valueBeginIndex;
				char valueEndChar;
				
				if(header[i] == '\"') { // Value is quoted
					valueEndChar = '\"';
					valueBeginIndex = ++i;
					
					while(header[i] != valueEndChar)
						++i;
					
				} else {
					valueEndChar = ';';
					valueBeginIndex = i;
					
					while(i < header.length && header[i] != valueEndChar)
						++i;
				}
				

				int valueEndIndex = i;
				
				while(valueEndIndex > valueBeginIndex && Character.isWhitespace(header[valueEndIndex-1])) // Remove trailing whitespace
					--valueEndIndex;

				value = new String(header, valueBeginIndex, valueEndIndex - valueBeginIndex);
				
				// We're done parsing the value, continue to the next character
				++i;
				
				// Skip whitespace between end of quotation and the semicolon following the quotation.
				if(valueEndChar == '\"') {
					while(i < header.length && header[i] != ';') {
						if(!Character.isWhitespace(header[i]))
							throw new ParseException("Invalid cookie: Missing terminating semicolon after value quotation: " + httpHeader, i);
						
						++i;
					}
					
					// We found the semicolon, skip it
					++i;
				}
					
			}
			else
				value = "";
			
			// RFC2965: Name MUST be first. Anything key besides the name of the cookie begins with $. The next cookie begins if a key occurs which is not
			// prefixed with $.
			
			if(currentCookieName == null) { // We have not found the name yet, the first key/value pair must be the name and the value of the cookie.
				if(key.charAt(0) == '$') {
					// We cannot throw because Konqueror (4.2.2) is broken and specifies $version as the first attribute.
					//throw new IllegalArgumentException("Invalid cookie: Name is not the first attribute: " + httpHeader);
					
					currentCookieContent.put(key, value);
				} else {
					currentCookieName = key;
					currentCookieContent.put(currentCookieName, value);
				}
			} else {
				if(key.charAt(0) == '$')
					currentCookieContent.put(key, value);
				else {// We finished parsing of the current cookie, a new one starts here.
					//if(singleCookie)
					//	throw new ParseException("Invalid cookie header: Multiple cookies specified but "
					//			+ " the name of the first cookie was not the first attribute: " + httpHeader, i);
					
					cookies.add(new ReceivedCookie(currentCookieName, currentCookieContent)); // Store the previous cookie.
					
					currentCookieName = key;
					currentCookieContent = new Hashtable<String, String>(16);
					currentCookieContent.put(currentCookieName, value);
				}
			}
		}
		}
		catch(ArrayIndexOutOfBoundsException e) {
			ParseException p = new ParseException("Index out of bounds (" + e.getMessage() + ") for cookie " + httpHeader, 0);
			p.setStackTrace(e.getStackTrace());
			throw p;
		}
		
		// Store the last cookie (the loop only stores the current cookie when a new one starts).
		if(currentCookieName != null)
			cookies.add(new ReceivedCookie(currentCookieName, currentCookieContent));
		
		return cookies;
	}

	
	/**
	 * @throws IllegalArgumentException If the validation of the name fails.
	 */
	@Override
	public String getName() {
		if(name == null) {
			name = validateName(notValidatedName);
			notValidatedName = null;
		}
		
		return name;
	}
	
	/**
	 * @throws IllegalArgumentException If the validation of the domain fails.
	 */
	@Override
	public URI getDomain() {
		if(domain == null) {
			try {
				String domainString = content.get("$domain");
				if(domainString == null)
					return null;
				
				domain = validateDomain(domainString);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(e);
			}
		}
		
		return domain;
	}

	/**
	 * @throws IllegalArgumentException If the validation of the path fails.
	 */
	@Override
	public URI getPath() {
		if(path == null) {
			try {
				path = validatePath(content.get("$path"));
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(e);
			}
		}
		
		return path;
	}

	/**
	 * @throws IllegalArgumentException If the validation of the name fails.
	 */
	@Override
	public String getValue() {
		if(value == null) 
			value = validateValue(content.get(getName()));
		
		return value;
	}

// TODO: This is broken because TimeUtil.parseHTTPDate() does not work.
//	public Date getExpirationDate() {
//		if(expirationDate == null) {
//			try {
//				expirationDate = validateExpirationDate(TimeUtil.parseHTTPDate(content.get("$expires")));
//			} catch (ParseException e) {
//				throw new IllegalArgumentException(e);
//			}
//		}
//		
//		return expirationDate;
//	}

	@Override
	protected String encodeToHeaderValue() {
		throw new UnsupportedOperationException("ReceivedCookie objects cannot be encoded to a HTTP header value, use Cookie objects!");
	}
	
}
