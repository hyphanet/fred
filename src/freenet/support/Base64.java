package freenet.support;

/**
* This class provides encoding of byte arrays into Base64-encoded strings,
* and decoding the other way.
*
* <P>NOTE!  This is modified Base64 with slightly different characters than
* usual, so it won't require escaping when used in URLs.
*
* <P>NOTE!  This class only does the padding that's normal in Base64
* if the 'true' flag is given to the encode() method.  This is because
* Base64 requires that the length of the encoded text be a multiple
* of four characters, padded with '='.  Without the 'true' flag, we don't
* add these '=' characters.
*
* @author Stephen Blackheath
*/
public class Base64 {
	private static char[] base64Alphabet = {
	'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
	'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
	'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
	'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
	'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
	'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
	'w', 'x', 'y', 'z', '0', '1', '2', '3',
	'4', '5', '6', '7', '8', '9', '~', '-'};

	private static char[] base64StandardAlphabet = {
	'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
	'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
	'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
	'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
	'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
	'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
	'w', 'x', 'y', 'z', '0', '1', '2', '3',
	'4', '5', '6', '7', '8', '9', '+', '/'};

	/**
	* A reverse lookup table to convert base64 letters back into the
	* a 6-bit sequence.
	*/
	private static byte[] base64Reverse;
	private static byte[] base64StandardReverse;

	// Populate the base64Reverse lookup table from the base64Alphabet table.
	static {
		base64Reverse = new byte[128];
		base64StandardReverse = new byte[base64Reverse.length];

		// Set all entries to 0xFF, which means that that particular letter
		// is not a legal base64 letter.
		for (int i = 0; i < base64Reverse.length; i++) {
			base64Reverse[i] = (byte) 0xFF;
			base64StandardReverse[i] = (byte) 0xFF;
		}
		for (int i = 0; i < base64Alphabet.length; i++) {
			base64Reverse[base64Alphabet[i]] = (byte) i;
			base64StandardReverse[base64StandardAlphabet[i]] = (byte) i;
		}
	}

	/**
	* Encode to our shortened (non-standards-compliant) format.
	*/
	public static String encode(byte[] in) {
		return encode(in, false);
	}

	/* FIXME: Figure out where this function is used and maybe remove it if its not
	* used. Its old javadoc which has been here for a while fools the user into believing
	* that the format is standard compliant */

	/**
	* Caller should specify equalsPad=true if they want a standards compliant padding,
	* but not standard compliant encoding.
	*/
	public static String encode(byte[] in, boolean equalsPad) {
		return encode(in, equalsPad, base64Alphabet);
	}

	/**
	* Standard compliant encoding.
	*/
	public static String encodeStandard(byte[] in) {
		return encode(in, true, base64StandardAlphabet);
	}

	/**
	* Caller should specify equalsPad=true if they want a standards compliant encoding.
	*/
	private static String encode(byte[] in, boolean equalsPad, char[] alphabet) {
		char[] out = new char[((in.length+2)/3)*4];
		int rem = in.length%3;
		int o = 0;
		for (int i = 0; i < in.length;) {
			int val = (in[i++] & 0xFF) << 16;
			if (i < in.length) {
				val |= (in[i++] & 0xFF) << 8;
			}
			if (i < in.length) {
				val |= (in[i++] & 0xFF);
			}
			out[o++] = alphabet[(val>>18) & 0x3F];
			out[o++] = alphabet[(val>>12) & 0x3F];
			out[o++] = alphabet[(val>>6) & 0x3F];
			out[o++] = alphabet[val & 0x3F];
		}
		int outLen = out.length;
		switch (rem) {
		case 1: outLen -= 2; break;
		case 2: outLen -= 1; break;
		}
		// Pad with '=' signs up to a multiple of four if requested.
		if (equalsPad) {
			while (outLen < out.length) {
				out[outLen++] = '=';
			}
		}
		return new String(out, 0, outLen);
	}

	/**
	* Handles the standards-compliant padding (padded with '=' signs) as well as our
	* shortened form.
	* @throws IllegalBase64Exception
	*/
	public static byte[] decode(String inStr) throws IllegalBase64Exception {
		return decode(inStr, base64Reverse);
	}

	/**
	* Handles the standards-compliant base64 encoding.
	*/
	public static byte[] decodeStandard(String inStr) throws IllegalBase64Exception {
		return decode(inStr, base64StandardReverse);
	}

	/**
	* Handles the standards-compliant (padded with '=' signs) as well as our
	* shortened form.
	*/
	private static byte[] decode(String inStr, byte[] reverseAlphabet)
			throws IllegalBase64Exception {
		try {
			char[] in = inStr.toCharArray();
			int inLength = in.length;

			// Strip trailing equals signs.
			while ((inLength > 0) && (in[inLength-1] == '='))
			inLength--;

			int blocks = inLength/4;
			int remainder = inLength & 3;
			// wholeInLen and wholeOutLen are the the length of the input and output
			// sequences respectively, not including any partial block at the end.
			int wholeInLen  = blocks*4;
			int wholeOutLen = blocks*3;
			int outLen = wholeOutLen;
			switch (remainder) {
			case 1: throw new IllegalBase64Exception("illegal Base64 length");
			case 2:  outLen = wholeOutLen+1; break;
			case 3:  outLen = wholeOutLen+2; break;
			default: outLen = wholeOutLen;
			}
			byte[] out = new byte[outLen];
			int o = 0;
			int i;
			for (i = 0; i < wholeInLen;) {
				int in1 = reverseAlphabet[in[i]];
				int in2 = reverseAlphabet[in[i+1]];
				int in3 = reverseAlphabet[in[i+2]];
				int in4 = reverseAlphabet[in[i+3]];
				int orValue = in1|in2|in3|in4;
				if ((orValue & 0x80) != 0) {
					throw new IllegalBase64Exception("illegal Base64 character");
				}
				int outVal = (in1 << 18) | (in2 << 12) | (in3 << 6) | in4;
				out[o] = (byte) (outVal>>16);
				out[o+1] = (byte) (outVal>>8);
				out[o+2] = (byte) outVal;
				i += 4;
				o += 3;
			}
			int orValue;
			switch (remainder) {
			case 2:	{
				int in1 = reverseAlphabet[in[i]];
				int in2 = reverseAlphabet[in[i+1]];
				orValue = in1|in2;
				int outVal = (in1 << 18) | (in2 << 12);
				out[o] = (byte) (outVal>>16);
				break;
			}
			case 3:{
				int in1 = reverseAlphabet[in[i]];
				int in2 = reverseAlphabet[in[i+1]];
				int in3 = reverseAlphabet[in[i+2]];
				orValue = in1|in2|in3;
				int outVal = (in1 << 18) | (in2 << 12) | (in3 << 6);
				out[o] = (byte) (outVal>>16);
				out[o+1] = (byte) (outVal>>8);
				break;
			}
			default:
				// Keep compiler happy
				orValue = 0;
			}
			if ((orValue & 0x80) != 0) {
				throw new IllegalBase64Exception("illegal Base64 character");
			}
			return out;
		// Illegal characters can cause an ArrayIndexOutOfBoundsException when
		// looking up reverseAlphabet.
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new IllegalBase64Exception("illegal Base64 character");
		}
	}
}
