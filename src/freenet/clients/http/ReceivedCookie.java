/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A cookie which the server has received from the client.
 *
 * @author xor (xor@freenetproject.org)
 */
public final class ReceivedCookie {

    private static final int ONE_MB_OF_ASCII_TEXT = 1048576;

    private final String name;
    private final String value;


    /**
     * Constructor for creating cookies from parsed key-value pairs.
     * <p>
     * Does not validate the names or values of the keys, each attribute is validated at the first call to it's getter method.
     * Therefore, no CPU time is wasted if the client sends cookies which we do not use.
     */
    private ReceivedCookie(String myName, String myValue) {
        if (myName == null) {
            throw new IllegalArgumentException("Cookie name is null");
        }
        if (myName.isEmpty()) {
            throw new IllegalArgumentException("Cookie name is empty");
        }
        name = myName;
        value = myValue;
    }

    /**
     * Parses the value of a "Cookie:" HTTP header and returns a list of received cookies which it contained.
     * - A single "Cookie:" header is allowed to contain multiple cookies. Further, a HTTP request can contain multiple "Cookie" keys!.
     *
     * @param httpHeader The value of a "Cookie:" header (i.e. the prefix "Cookie:" must not be contained in this parameter!)
     * @return A list of {@link ReceivedCookie} objects. The validity of their name/value pairs is not deeply checked, their getName() / getValue() might throw!
     * @throws ParseException If the general formatting of the cookie is wrong.
     */
    static List<ReceivedCookie> parseHeader(String httpHeader) throws ParseException {
        if (httpHeader == null || httpHeader.isEmpty()) {
            return Collections.emptyList();
        }
        if (httpHeader.length() > ONE_MB_OF_ASCII_TEXT) {
            throw new IllegalArgumentException("Cookie value is too long. Length: " + httpHeader.length());
        }
        char[] header = httpHeader.toCharArray();
        List<ReceivedCookie> cookies = new ArrayList<>(4); // TODO: Adjust to the usual amount of cookies which fred uses + 1

        // We do manual parsing instead of using regular expressions for two reasons:
        // 1. The value of cookies can be quoted, therefore it is a context-free language and not a regular language - we cannot express it with a regexp!
        // 2. Its very fast :)

        String key = null;
        String value = null;
        try {
            for (int i = 0; i < header.length; ) {
                // Skip leading whitespace of key, we must do a header.length check because there might be no more key, so we continue;
                if (Character.isWhitespace(header[i])) {
                    ++i;
                    continue;
                }

                // Parse key
                {
                    int keyBeginIndex = i;

                    while (i < header.length && header[i] != '=' && header[i] != ';') {
						++i;
					}

                    int keyEndIndex = i;

					// Remove trailing whitespace
                    while (Character.isWhitespace(header[keyEndIndex - 1])) {
						--keyEndIndex;
					}

                    key = new String(header, keyBeginIndex, keyEndIndex - keyBeginIndex);

                    if (key.length() == 0) {
						throw new ParseException("Invalid cookie: Contains an empty key: " + httpHeader, i);
					}

                    // We're done parsing the key, continue to the next character.
                    ++i;
                }

                // Parse value (empty values are allowed).
                if (i < header.length) {
					// Skip leading whitespace
                    while (Character.isWhitespace(header[i])) {
						++i;
					}

                    int valueBeginIndex;
                    char valueEndChar;

                    if (header[i] == '\"') { // Value is quoted
                        valueEndChar = '\"';
                        valueBeginIndex = ++i;

                        while (header[i] != valueEndChar) {
							++i;
						}

                    } else {
                        valueEndChar = ';';
                        valueBeginIndex = i;

                        while (i < header.length && header[i] != valueEndChar) {
							++i;
						}
                    }


                    int valueEndIndex = i;

					// Remove trailing whitespace
                    while (valueEndIndex > valueBeginIndex && Character.isWhitespace(header[valueEndIndex - 1])) {
						--valueEndIndex;
					}

                    value = new String(header, valueBeginIndex, valueEndIndex - valueBeginIndex);

                    // We're done parsing the value, continue to the next character
                    ++i;

                    // Skip whitespace between end of quotation and the semicolon following the quotation.
                    if (valueEndChar == '\"') {
						while (i < header.length && header[i] != ';') {
							if (!Character.isWhitespace(header[i])) {
								throw new ParseException("Invalid cookie: Missing terminating semicolon after value quotation: " + httpHeader, i);
							}
							++i;
						}

						// We found the semicolon, skip it
						++i;
					}
                }

				cookies.add(new ReceivedCookie(key, value));
				key = null;
				value = null;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            ParseException p = new ParseException("Index out of bounds (" + e.getMessage() + ") for cookie " + httpHeader, 0);
            p.setStackTrace(e.getStackTrace());
            throw p;
        }

        // Store the last cookie (the loop only stores the current cookie when a new one starts).
        if (key != null) {
            cookies.add(new ReceivedCookie(key, value));
        }

        return cookies;
    }

    /**
     * @return cookie name
     */
    public String getName() {
        return name;
    }

    /**
     * @return cookie value, may be {@code null} or empty
     */
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
			return true;
		}
        if (o == null || getClass() != o.getClass()) {
			return false;
		}
        ReceivedCookie that = (ReceivedCookie) o;
        return name.equals(that.name) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }
}
