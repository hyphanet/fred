/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;


/**
 * @author xor (xor@freenetproject.org)
 */
public class Cookie {

    /**
     * Cookie token separators from https://www.ietf.org/rfc/rfc2616.html#section-2.2
     */
    static final Set<Character> COOKIE_TOKEN_SEPARATOR_CHARACTERS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                '(', ')', '<', '>', '@', ',', ';', ':', '\\', '\"', '/', '[', ']', '?', '=', '{', '}', ' ', '\t'
            )
        )
    );

    /**
     * Cookie value forbidden chars excluding ASCII control characters and space.
     * From https://www.ietf.org/rfc/rfc6265.html#section-4.1.1
     */
    static final Set<Character> COOKIE_VALUE_FORBIDDEN_CHARS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList('\"', ',', ';', '\\'))
    );

    /**
     * Reserved cookie attribute names as for https://www.ietf.org/rfc/rfc2965.html#section-3.2
     * (in lower case)
     */
    private static final Set<String> RESERVED_ATTRIBUTE_NAMES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "comment", "commenturl", "discard", "domain", "expires", "max-age", "path", "port", "secure", "version"
            )
        )
    );


    protected int version;

    protected URI domain;

    protected URI path;

    protected String name;

    protected String value;

    protected Instant expirationTime;

    protected boolean discard;


    /**
     * Constructor for creating cookies which are to be used in {@link ToadletContext.setCookie}.
     *
     * @param myPath  The path of the cookie. Must be absolute.
     * @param myName  The name of the cookie. Must be latin letters and numbers only and is converted to lowercase.
     * @param myValue The value of the cookie. Must not contain any linebreaks. May be null, null means empty value.
     */
    public Cookie(URI myPath, String myName, String myValue, Instant myExpirationTime) {
        version = 1;
        domain = null;
        path = validatePath(myPath);
        name = validateName(myName);
        value = myValue != null ? validateValue(myValue) : "";
        expirationTime = validateExpirationDate(myExpirationTime);
        discard = true; // Freenet cookies shall always be discarded when the browser is closed;
    }

    /**
     * Returns true if two Cookies have equal domain, path and name. Does not check the value!
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (!(obj instanceof Cookie)) {
            return false;
        }

        Cookie other = (Cookie) obj;

        // RFC2965: Two cookies are equal if name and domain are equal with case-insensitive comparison and path is equal with case-sensitive comparison.
        // We don't have to do anything about the case here though because getName() / getDomain() returns lowercase and getPath() returns the original path.

        URI myDomain = getDomain();
        URI otherDomain = other.getDomain();

        if (myDomain != null) {
            if (otherDomain == null || !otherDomain.toString().equals(myDomain.toString())) {
                return false;
            }
        } else if (otherDomain != null) {
            return false;
        }

        if (!getPath().toString().equals(other.getPath().toString())) {
            return false;
        }

        return getName().equals(other.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.domain, this.path, this.name);
    }

    protected static URI validateDomain(String domainString) throws URISyntaxException {
        return validateDomain(new URI(domainString.toLowerCase()));
    }

    protected static URI validateDomain(URI domain) {
        String scheme = domain.getScheme().toLowerCase();

        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Illegal cookie domain, must be http or https: " + domain);
        }

        String path = domain.getPath();

        if (!"".equals(path) && !"/".equals(path)) {
            throw new IllegalArgumentException("Illegal cookie domain, contains a path: " + domain);
        }

        return domain;
    }

    protected static URI validatePath(URI path) {
        // FIXME: Be more restrictive.

        if (path == null) {
            throw new IllegalArgumentException("Illegal cookie path, must be present but actual value is null");
        }

        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Illegal cookie path, must be relative: " + path);
        }

        if (!path.toString().startsWith("/")) {
            throw new IllegalArgumentException("Illegal cookie path, must start with /: " + path);
        }

        // RFC2965: Path is case sensitive!

        return path;
    }


    /**
     * Validates the name of a cookie against a mixture of RFC2965, RFC2616 and RFC6265 (from IETF.org)
     * and personal choice :|
     * The personal choice is mostly that it uses isISOControl for determining control characters instead of the
     * list in the RFC - therefore, more characters are considered as control characters.
     * So effectively this function is more restrictive than the RFCs.
     * TODO: Read the RFCs in depth and make this function fully compatible.
     */
    protected static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Name is null.");
        }

        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name is empty.");
        }

        if (!isUSASCII(name)) {
            throw new IllegalArgumentException("Invalid name, contains non-US-ASCII characters: " + name);
        }

        name = name.trim().toLowerCase(); // RFC2965: Name is case-insensitive

        for (Character c : name.toCharArray()) {
            if (Character.isWhitespace(c)) {
                throw new IllegalArgumentException("Invalid name, contains whitespace: " + name);
            }

            // From isISOControl javadoc: A character is considered to be an ISO control character if its in the range [0,31] or [127,159]
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Invalid name, contains control characters.");
            }

            if (COOKIE_TOKEN_SEPARATOR_CHARACTERS.contains(c)) {
                throw new IllegalArgumentException("Invalid name, contains one of the explicitely disallowed characters: " + name);
            }
        }

        if (name.startsWith("$") || RESERVED_ATTRIBUTE_NAMES.contains(name)) {
            throw new IllegalArgumentException("Name is reserved: " + name);
        }

        return name;
    }

    private static boolean isUSASCII(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Java chars are unicode. Unicode is a superset of US-ASCII.
            if (c < 32 || c > 126) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the value of a cookie against a mixture of RFC2965, RFC2616 and RFC6265 (from IETF.org)
     * and personal choice :|
     * The personal choice is mostly that it uses isISOControl for determining control characters instead of the
     * list in the RFC - therefore, more characters are considered as control characters.
     * So effectively this function is more restrictive than the RFCs.
     * TODO: Read the RFCs in depth and make this function fully compatible.
     */
    protected static String validateValue(String value) {
        if (!isUSASCII(value)) {
            throw new IllegalArgumentException("Invalid value, contains non-US-ASCII characters: " + value);
        }

        value = value.trim();

        for (Character c : value.toCharArray()) {
            // We allow whitespace in the value because quotation is allowed and supported by the parser in ReceivedCookie

            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Invalid value, contains control characters.");
            }

            if (COOKIE_VALUE_FORBIDDEN_CHARS.contains(c)) {
                throw new IllegalArgumentException("Invalid value, contains one of the explicitly disallowed characters: " + value);
            }
        }

        return value;
    }

    protected static Instant validateExpirationDate(Instant expirationTime) {
        if (expirationTime == null) {
            throw new IllegalArgumentException("Illegal expiration time, value is null");
        }
        return expirationTime;
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

    public Instant getExpirationTime() {
        return expirationTime;
    }

    /**
     * Encodes the content of this cookie to the HTTP header value representation as of RFC2965.
     * Does not quote any of the values as Firefox 4 does not support quotation and the latest IETF draft for cookies does not contain
     * quotation: http://tools.ietf.org/html/draft-ietf-httpstate-cookie-23
     * The key "set-cookie2:" is not prefixed to the returned String!
     */
    protected String encodeToHeaderValue() {
        StringBuilder sb = new StringBuilder(512); // TODO: As soon as something else besides Freetalk uses cookies, adjust this value.

        // RFC2965: Name MUST be first.
        sb.append(name);
        sb.append("=");
        sb.append(value);
        sb.append(';');

        sb.append("version=");
        sb.append(version);
        sb.append(';');

        if (domain != null) {
            sb.append("domain=");
            sb.append(domain);
            sb.append(';');
        }

        sb.append("path=");
        sb.append(path);
        sb.append(';');

        sb.append("expires=");
        sb.append(DateTimeFormatter.RFC_1123_DATE_TIME.format(expirationTime.atOffset(ZoneOffset.UTC)));
        sb.append(';');

        if (discard) {
            sb.append("discard=");
            sb.append(discard);
            sb.append(';');
        }

        return sb.toString();
    }
}
