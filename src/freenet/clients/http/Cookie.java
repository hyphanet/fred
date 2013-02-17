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
import freenet.support.TimeUtil;

/**
 * @author xor (xor@freenetproject.org)
 */
public class Cookie {
	/**
	 * FIXME: Where was this taken from?
	 */
	private static final HashSet<Character> invalidValueCharacters =
		new HashSet<Character>(Arrays.asList(new Character[] { '(', ')', '[', ']', '{', '}', '=', ',', '\"', '/', '\\', '?', '@', ':', ';' }));
	
	/**
	 * Taken from this discussion:
		<TheSeeker>        CTL            = <any US-ASCII control character
		<TheSeeker>                         (octets 0 - 31) and DEL (127)>
		<TheSeeker>        CHAR           = <any US-ASCII character (octets 0 - 127)>
		<TheSeeker>        token          = 1*<any CHAR except CTLs or separators>
		<TheSeeker>        separators     = "(" | ")" | "<" | ">" | "@" | "," | ";" | ":" | "\" | <"> | "/" | "[" | "]" | "?" | "=" | "{" | "}" | SP | HT
		<TheSeeker> so, anything from 32-126 that isn't in that list of seperators is valid.
		<p0s> TheSeeker: where did you copy that from?
		<TheSeeker> http://www.ietf.org/rfc/rfc2616.txt
	*/
	public static final HashSet<Character> httpSeparatorCharacters =
		new HashSet<Character>(Arrays.asList(new Character[] { '(', ')', '<', '>', '@', ',', ';', ':', '\\', '\"', '/', '[', ']', '?', '=', '{', '}', ' ', '\t' }));
	
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
	@Override
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
	
	@Override
	public int hashCode() {
		return domain.hashCode() + path.hashCode() + name.hashCode();
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
	

	/**
	 * Validates the name of a cookie against a mixture of RFC2965, RFC2616 (from IETF.org) and personal choice :|
	 * The personal choice is mostly that it uses isISOControl for determining control characters instead of the
	 * list in the RFC - therefore, more characters are considered as control characters. 
	 * So effectively this function is more restrictive than the RFCs.
	 * TODO: Read the RFCs in depth and make this function fully compatible.
	 */
	public static String validateName(String name) {
		if("".equals(name))
			throw new IllegalArgumentException("Name is empty.");
		
		if(!isUSASCII(name))
			throw new IllegalArgumentException("Invalid name, contains non-US-ASCII characters: " + name);
		
		name = name.trim().toLowerCase(); // RFC2965: Name is case insensitive
		
		/*
		<TheSeeker>        CTL            = <any US-ASCII control character
		<TheSeeker>                         (octets 0 - 31) and DEL (127)>
		<TheSeeker>        CHAR           = <any US-ASCII character (octets 0 - 127)>
		<TheSeeker>        token          = 1*<any CHAR except CTLs or separators>
		<TheSeeker>        separators     = "(" | ")" | "<" | ">" | "@" | "," | ";" | ":" | "\" | <"> | "/" | "[" | "]" | "?" | "=" | "{" | "}" | SP | HT
		<TheSeeker> so, anything from 32-126 that isn't in that list of seperators is valid.
		<p0s> TheSeeker: where did you copy that from?
		<TheSeeker> http://www.ietf.org/rfc/rfc2616.txt
		<TheSeeker> The following grammar uses the notation, and tokens DIGIT (decimal digits), token (informally, a sequence of non-special, non-white space characters), and http_URL from the HTTP/1.1 specification [RFC2616] to describe their syntax.
		<TheSeeker>        quoted-string  = ( <"> *(qdtext | quoted-pair ) <"> )
		<TheSeeker>        qdtext         = <any TEXT except <">>
		<TheSeeker>        TEXT           = <any OCTET except CTLs, but including LWS>
		<TheSeeker>        LWS            = [CRLF] 1*( SP | HT )
		<TheSeeker>        OCTET          = <any 8-bit sequence of data>
		<TheSeeker> so, if it's quoted, it can be anything 32-126 and 128-255 (except a quote char, obviously)
		*/

		for(Character c : name.toCharArray()) {
			if(Character.isWhitespace(c))
				throw new IllegalArgumentException("Invalid name, contains whitespace: " + name);
			
			// From isISOControl javadoc: A character is considered to be an ISO control character if its in the range [0,31] or [127,159]
			if(Character.isISOControl(c))
				throw new IllegalArgumentException("Invalid name, contains control characters.");
			
			if(httpSeparatorCharacters.contains(c))
				throw new IllegalArgumentException("Invalid name, contains one of the explicitely disallowed characters: " + name);
		}
		
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
	
	private static boolean isUSASCII(String name) {
		for(int i=0;i<name.length();i++) {
			char c = name.charAt(i);
			// Java chars are unicode. Unicode is a superset of US-ASCII.
			if(c < 32 || c > 126)
				return false;
		}
		return true;
	}

	/**
	 * Validates the value of a cookie against a mixture of RFC2965, RFC2616 (from IETF.org) and personal choice :|
	 * The personal choice is mostly that it uses isISOControl for determining control characters instead of the
	 * list in the RFC - therefore, more characters are considered as control characters. 
	 * So effectively this function is more restrictive than the RFCs.
	 * TODO: Read the RFCs in depth and make this function fully compatible.
	 */
	public static String validateValue(String value) {
		if(!isUSASCII(value))
			throw new IllegalArgumentException("Invalid value, contains non-US-ASCII characters: " + value);
		
		value = value.trim();

		/*
		<TheSeeker>        CTL            = <any US-ASCII control character
		<TheSeeker>                         (octets 0 - 31) and DEL (127)>
		<TheSeeker>        CHAR           = <any US-ASCII character (octets 0 - 127)>
		<TheSeeker>        token          = 1*<any CHAR except CTLs or separators>
		<TheSeeker>        separators     = "(" | ")" | "<" | ">" | "@" | "," | ";" | ":" | "\" | <"> | "/" | "[" | "]" | "?" | "=" | "{" | "}" | SP | HT
		<TheSeeker> so, anything from 32-126 that isn't in that list of seperators is valid.
		<p0s> TheSeeker: where did you copy that from?
		<TheSeeker> http://www.ietf.org/rfc/rfc2616.txt
		<TheSeeker> The following grammar uses the notation, and tokens DIGIT (decimal digits), token (informally, a sequence of non-special, non-white space characters), and http_URL from the HTTP/1.1 specification [RFC2616] to describe their syntax.
		<TheSeeker>        quoted-string  = ( <"> *(qdtext | quoted-pair ) <"> )
		<TheSeeker>        qdtext         = <any TEXT except <">>
		<TheSeeker>        TEXT           = <any OCTET except CTLs, but including LWS>
		<TheSeeker>        LWS            = [CRLF] 1*( SP | HT )
		<TheSeeker>        OCTET          = <any 8-bit sequence of data>
		<TheSeeker> so, if it's quoted, it can be anything 32-126 and 128-255 (except a quote char, obviously)
		*/

		for(Character c : value.toCharArray()) {
			// We allow whitespace in the value because quotation is allowed and supported by the parser in ReceivedCookie

			if(Character.isISOControl(c))
				throw new IllegalArgumentException("Invalid value, contains control characters.");
			
			// TODO: The source of the invalid value characters list is not mentioned in its javadoc - it has to be re-validated
			if(invalidValueCharacters.contains(c))
				throw new IllegalArgumentException("Invalid value, contains one of the explicitely disallowed characters: " + value);
		}
		
		return value;
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
	 * Does not quote any of the values as Firefox 4 does not support quotation and the latest IETF draft for cookies does not contain
	 * quotation: http://tools.ietf.org/html/draft-ietf-httpstate-cookie-23
	 * The key "set-cookie2:" is not prefixed to the returned String!
	 */
	protected String encodeToHeaderValue() {
		StringBuilder sb = new StringBuilder(512); // TODO: As soon as something else besides Freetalk uses cookies, adjust this value.
		
		// RFC2965: Name MUST be first.
		sb.append(name); sb.append("="); sb.append(value); sb.append(';');
		
		sb.append("version="); sb.append(version); sb.append(';');
		
		if(domain != null) {
			sb.append("domain="); sb.append(domain); sb.append(';');
		}
		
		sb.append("path="); sb.append(path); sb.append(';');
		
		sb.append("expires="); sb.append(TimeUtil.makeHTTPDate(expirationDate.getTime())); sb.append(';');
		
		if(discard) {
			sb.append("discard="); sb.append(discard); sb.append(';');
		}
		
		return sb.toString();
	}

}
