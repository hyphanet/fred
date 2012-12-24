package freenet.client.tools;

/**
 * Simple class for UTF-8+Base64 encoding and decoding, the same as what FProxy does.
 * UTF-8 encode/decode from http://ecmanaut.blogspot.co.uk/2006/07/encoding-decoding-utf8-in-javascript.html
 */
public class Base64 {
	// public method for decoding
	// input is assumed to be Base64(UTF-8(text))
	public static native String decode(String input) /*-{
		return decodeURIComponent(escape(atob(input)));
	}-*/;

	// public method for encoding
	public static native String encode(String input) /*-{
		return btoa(unescape(encodeURIComponent(input)));
	}-*/;
}
