/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;

import freenet.support.CurrentTimeUTC;
import freenet.support.StringValidityChecker;
import freenet.support.TimeUtil;

public class Cookie {
	
	private static final HashSet<Character> invalidValueCharacters =
		new HashSet<Character>(Arrays.asList(new Character[] { '(', ')', '[', ']', '{', '}', '=', ',', '\"', '/', '\\', '?', '@', ':', ';' }));
	
	private static final Charset usasciiCharset = Charset.forName("US-ASCII");
	
	
	protected int version;
	
	protected URI domain;
	
	protected URI path;
	
	protected String name;
	
	protected String value;
	
	protected Date expirationDate;
	
	protected boolean discard;
	
	
	/**
	 * Constructor for creating cookies which are to be used in {@link ToadletContext.setCookie}.
	 * 
	 * @param myPath The path of the cookie. Must be absolute.
	 * @param myName The name of the cookie. Must be latin letters and numbers only and is converted to lowercase.
	 * @param myValue The value of the cookie. Must not contain any linebreaks. May be null, null means empty value.
	 * @throws URISyntaxException 
	 */
	public Cookie(URI myPath, String myName, String myValue, Date myExpirationDate) {
		version = 1;
		
		domain = null;
		path = validatePath(myPath);
		name = validateName(myName);
		value = myValue != null ? validateValue(myValue) : "";
		expirationDate = validateExpirationDate(myExpirationDate);
		discard = true; // Freenet cookies shall always be discarded when the browser is closed;
	}
	
	protected Cookie() {
		
	}

	/**
	 * Returns true if two Cookies have equal domain, path and name. Does not check the value!
	 */
	public boolean equals(Object obj) {
		if(obj == this)
			return true;
		
		if(!(obj instanceof Cookie))
			return false;
		
		Cookie other = (Cookie)obj;
		
		// RFC2965: Two cookies are equal if name and domain are equal with case-insensitive comparison and path is equal with case-sensitive comparison.
		// We don't have to do anything about the case here though because getName() / getDomain() returns lowercase and getPath() returns the original path.
		
		URI myDomain = getDomain(); URI otherDomain = other.getDomain();
		
		if(myDomain != null) {
			if(otherDomain == null || !otherDomain.toString().equals(myDomain.toString()))
				return false;
		} else if(otherDomain != null)
			return false;
		
		
		if(!getPath().toString().equals(other.getPath().toString()))
			return false;
		
		if(!getName().equals(other.getName()))
			return false;
		
		return true;
	}

	public static URI validateDomain(String domainString) throws URISyntaxException {
		return validateDomain(new URI(domainString.toLowerCase()));	
	}
	
	public static URI validateDomain(URI domain) {
		String scheme = domain.getScheme().toLowerCase();
		
		if(!"http".equals(scheme) && !"https".equals(scheme))
			throw new IllegalArgumentException("Illegal cookie domain, must be http or https: " + domain);
		
		String path = domain.getPath();
		
		if(!"".equals(path) && !"/".equals(path))
			throw new IllegalArgumentException("Illegal cookie domain, contains a path: " + domain);
		
		return domain;
	}
	
	public static URI validatePath(String stringPath) throws URISyntaxException {
		return validatePath(new URI(stringPath));
	}
	
	public static URI validatePath(URI path) {
		// FIXME: Be more restrictive.
		
		if(path.isAbsolute())
			throw new IllegalArgumentException("Illegal cookie path, must be relative: " + path);
		
		if(path.toString().startsWith("/") == false)
			throw new IllegalArgumentException("Illegal cookie path, must start with /: " + path);
		
		// RFC2965: Path is case sensitive!
		
		return path;
	}
	
	public static String validateName(String name) {
		name = name.trim().toLowerCase(); // RFC2965: Name is case insensitive

		if("".equals(name))
			throw new IllegalArgumentException("Name is empty.");

		// FIXME: This is more restrictive than the official allowed content of a cookie name because I was too lazy for finding out the exact requirements.
		if(StringValidityChecker.isLatinLettersAndNumbersOnly(name) == false)
			throw new IllegalArgumentException("Only letters and numbers are allowed as name, found: " + name);
		
		if(		name.startsWith("$")
				|| "comment".equals(name)
				|| "discard".equals(name)
				|| "domain".equals(name)
				|| "expires".equals(name)
				|| "max-age".equals(name)
				|| "path".equals(name)
				|| "secure".equals(name)
				|| "version".equals(name)
				)
			throw new IllegalArgumentException("Name is reserved: " + name);
		
		return name;
	}
	
	public static String validateValue(String value) {
		String newValue = new String(usasciiCharset.encode(value).array());
		
		if(newValue.equals(value) == false)
			throw new IllegalArgumentException("Invalid value, contains non-US-ASCII characters: " + value);
		
		newValue = newValue.trim();
	
		// FIXME: This is more restrictive than the official allowed content of a value because I was too lazy for finding out the exact requirements.
		
		for(Character c : newValue.toCharArray()) {
			if(Character.isWhitespace(c))
				throw new IllegalArgumentException("Invalid value, contains whitespace: " + value);
			
			if(Character.isISOControl(c))
				throw new IllegalArgumentException("Invalid value, contains control characters.");
			
			if(invalidValueCharacters.contains(c))
				throw new IllegalArgumentException("Invalid value, contains one of the explicitely disallowed characters: " + value);
		}
		
		return newValue;
	}
	
	public static Date validateExpirationDate(Date expirationDate) {
		if(CurrentTimeUTC.get().after(expirationDate))
			throw new IllegalArgumentException("Illegal expiration date, is in past: " + expirationDate);
		
		return expirationDate;
	}
	
	public URI getDomain() {
		return domain;
	}

	public URI getPath() {
		return path;
	}

	public String getName() {
		return name;
	}

	public String getValue() {
		return value;
	}
	
// TODO: This is broken in ReceivedCookie so it is also commented out here. See ReceivedCookie for the explanation.
//	public Date getExpirationDate() {
//		return expirationDate;
//	}

	
	/**
	 * Encodes the content of this cookie to the HTTP header value representation as of RFC2965.
	 * The key "set-cookie2:" is not prefixed to the returned String!
	 */
	protected String encodeToHeaderValue() {
		StringBuilder sb = new StringBuilder(512); // TODO: As soon as something else besides Freetalk uses cookies, adjust this value.
		
		// RFC2965: Name MUST be first.
		sb.append(name); sb.append("=\""); sb.append(value); sb.append('\"'); sb.append(';');
		
		sb.append("version="); sb.append(version); sb.append(';');
		
		if(domain != null) {
			sb.append("domain=\""); sb.append(domain); sb.append('\"'); sb.append(';');
		}
		
		sb.append("path=\""); sb.append(path); sb.append('\"'); sb.append(';');
		
		sb.append("expires=\""); sb.append(TimeUtil.makeHTTPDate(expirationDate.getTime())); sb.append('\"'); sb.append(';');
		
		if(discard) {
			sb.append("discard="); sb.append(discard); sb.append(';');
		}
		
		return sb.toString();
	}

}
