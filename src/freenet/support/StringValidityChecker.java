package freenet.support;

import java.util.Arrays;
import java.util.HashSet;

public final class StringValidityChecker {
	
	/**
	 * Taken from http://kb.mozillazine.org/Network.IDN.blacklist_chars
	 */
	private static final HashSet<Character> idnBlacklist = new HashSet<Character>(Arrays.asList(
			new Character[] {
					0x0020, /* SPACE */
					0x00A0, /* NO-BREAK SPACE */
					0x00BC, /* VULGAR FRACTION ONE QUARTER */
					0x00BD, /* VULGAR FRACTION ONE HALF */
					0x01C3, /* LATIN LETTER RETROFLEX CLICK */
					0x0337, /* COMBINING SHORT SOLIDUS OVERLAY */
					0x0338, /* COMBINING LONG SOLIDUS OVERLAY */
					0x05C3, /* HEBREW PUNCTUATION SOF PASUQ */
					0x05F4, /* HEBREW PUNCTUATION GERSHAYIM */
					0x06D4, /* ARABIC FULL STOP */
					0x0702, /* SYRIAC SUBLINEAR FULL STOP */
					0x115F, /* HANGUL CHOSEONG FILLER */
					0x1160, /* HANGUL JUNGSEONG FILLER */
					0x2000, /* EN QUAD */
					0x2001, /* EM QUAD */
					0x2002, /* EN SPACE */
					0x2003, /* EM SPACE */
					0x2004, /* THREE-PER-EM SPACE */
					0x2005, /* FOUR-PER-EM SPACE */
					0x2006, /* SIX-PER-EM-SPACE */
					0x2007, /* FIGURE SPACE */
					0x2008, /* PUNCTUATION SPACE */
					0x2009, /* THIN SPACE */
					0x200A, /* HAIR SPACE */
					0x200B, /* ZERO WIDTH SPACE */
					0x2024, /* ONE DOT LEADER */
					0x2027, /* HYPHENATION POINT */
					0x2028, /* LINE SEPARATOR */
					0x2029, /* PARAGRAPH SEPARATOR */
					0x202F, /* NARROW NO-BREAK SPACE */
					0x2039, /* SINGLE LEFT-POINTING ANGLE QUOTATION MARK */
					0x203A, /* SINGLE RIGHT-POINTING ANGLE QUOTATION MARK */
					0x2044, /* FRACTION SLASH */
					0x205F, /* MEDIUM MATHEMATICAL SPACE */
					0x2154, /* VULGAR FRACTION TWO THIRDS */
					0x2155, /* VULGAR FRACTION ONE FIFTH */
					0x2156, /* VULGAR FRACTION TWO FIFTHS */
					0x2159, /* VULGAR FRACTION ONE SIXTH */
					0x215A, /* VULGAR FRACTION FIVE SIXTHS */
					0x215B, /* VULGAR FRACTION ONE EIGTH */
					0x215F, /* FRACTION NUMERATOR ONE */
					0x2215, /* DIVISION SLASH */
					0x23AE, /* INTEGRAL EXTENSION */
					0x29F6, /* SOLIDUS WITH OVERBAR */
					0x29F8, /* BIG SOLIDUS */
					0x2AFB, /* TRIPLE SOLIDUS BINARY RELATION */
					0x2AFD, /* DOUBLE SOLIDUS OPERATOR */
					0x2FF0, /* IDEOGRAPHIC DESCRIPTION CHARACTER LEFT TO RIGHT */
					0x2FF1, /* IDEOGRAPHIC DESCRIPTION CHARACTER ABOVE TO BELOW */
					0x2FF2, /* IDEOGRAPHIC DESCRIPTION CHARACTER LEFT TO MIDDLE AND RIGHT */
					0x2FF3, /* IDEOGRAPHIC DESCRIPTION CHARACTER ABOVE TO MIDDLE AND BELOW */
					0x2FF4, /* IDEOGRAPHIC DESCRIPTION CHARACTER FULL SURROUND */
					0x2FF5, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM ABOVE */
					0x2FF6, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM BELOW */
					0x2FF7, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM LEFT */
					0x2FF8, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM UPPER LEFT */
					0x2FF9, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM UPPER RIGHT */
					0x2FFA, /* IDEOGRAPHIC DESCRIPTION CHARACTER SURROUND FROM LOWER LEFT */
					0x2FFB, /* IDEOGRAPHIC DESCRIPTION CHARACTER OVERLAID */
					0x3000, /* IDEOGRAPHIC SPACE */
					0x3002, /* IDEOGRAPHIC FULL STOP */
					0x3014, /* LEFT TORTOISE SHELL BRACKET */
					0x3015, /* RIGHT TORTOISE SHELL BRACKET */
					0x3033, /* VERTICAL KANA REPEAT MARK UPPER HALF */
					0x3164, /* HANGUL FILLER */
					0x321D, /* PARENTHESIZED KOREAN CHARACTER OJEON */
					0x321E, /* PARENTHESIZED KOREAN CHARACTER O HU */
					0x33AE, /* SQUARE RAD OVER S */
					0x33AF, /* SQUARE RAD OVER S SQUARED */
					0x33C6, /* SQUARE C OVER KG */
					0x33DF, /* SQUARE A OVER M */
					0xFE14, /* PRESENTATION FORM FOR VERTICAL SEMICOLON */
					0xFE15, /* PRESENTATION FORM FOR VERTICAL EXCLAMATION MARK */
					0xFE3F, /* PRESENTATION FORM FOR VERTICAL LEFT ANGLE BRACKET */
					0xFE5D, /* SMALL LEFT TORTOISE SHELL BRACKET */
					0xFE5E, /* SMALL RIGHT TORTOISE SHELL BRACKET */
					0xFEFF, /* ZERO-WIDTH NO-BREAK SPACE */
					0xFF0E, /* FULLWIDTH FULL STOP */
					0xFF0F, /* FULL WIDTH SOLIDUS */
					0xFF61, /* HALFWIDTH IDEOGRAPHIC FULL STOP */
					0xFFA0, /* HALFWIDTH HANGUL FILLER */
					0xFFF9, /* INTERLINEAR ANNOTATION ANCHOR */
					0xFFFA, /* INTERLINEAR ANNOTATION SEPARATOR */
					0xFFFB, /* INTERLINEAR ANNOTATION TERMINATOR */
					0xFFFC, /* OBJECT REPLACEMENT CHARACTER */
					0xFFFD, /* REPLACEMENT CHARACTER */
			}));
	
	/**
	 * Taken from http://en.wikipedia.org/w/index.php?title=Filename&oldid=344618757
	 */
	private static final HashSet<Character> windowsReservedPrintableFilenameCharacters = new HashSet<Character>(Arrays.asList(
			new Character[] { '/', '\\', '?', '*', ':', '|', '\"', '<', '>'}));

	/**
	 * Taken from http://en.wikipedia.org/w/index.php?title=Filename&oldid=344618757
	 */
	private static final HashSet<String> windowsReservedFilenames = new HashSet<String>(Arrays.asList(
			new String[] { "aux", "clock$", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "con",
					"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "nul", "prn"}));
	
	/**
	 * Taken from http://en.wikipedia.org/w/index.php?title=Filename&oldid=344618757
	 */
	private static final HashSet<Character> macOSReservedPrintableFilenameCharacters = new HashSet<Character>(Arrays.asList(
			new Character[] { ':', '/'}));

	
	/**
	 * Returns true if the given character is one of the reserved printable character in filenames on Windows.
	 * ATTENTION: This function does NOT check whether the given character is a control character, those are also forbidden!
	 * (Control characters are usually disallowed for all operating systems in filenames by our validity checker so it checks them separately)   
	 */
	public static boolean isWindowsReservedPrintableFilenameCharacter(Character c) {
		return windowsReservedPrintableFilenameCharacters.contains(c);
	}
	
	public static boolean isWindowsReservedFilename(String filename) {
		filename = filename.toLowerCase();
		int nameEnd = filename.indexOf('.'); // For files with multiple dots, the part before the first dot counts as the filename. E.g. "con.blah.txt" is reserved.
		if(nameEnd == -1)
			nameEnd = filename.length();
		
		return windowsReservedFilenames.contains(filename.substring(0, nameEnd));
	}

	/**
	 * Returns true if the given character is one of the reserved printable character in filenames on Mac OS.
	 * ATTENTION: This function does NOT check whether the given character is a control character, those are also forbidden!
	 * (Control characters are usually disallowed for all operating systems in filenames by our validity checker so it checks them separately)
	 */
	public static boolean isMacOSReservedPrintableFilenameCharacter(Character c) {
		return macOSReservedPrintableFilenameCharacters.contains(c);
	}
	
	public static boolean isUnixReservedPrintableFilenameCharacter(char c) {
		return c == '/';
	}
	
	public static boolean containsNoIDNBlacklistCharacters(String text) {
		for(Character c : text.toCharArray()) {
			if(idnBlacklist.contains(c))
				return false;
		}
		
		return true;
	}
	
	public static boolean containsNoLinebreaks(String text) {
		for(Character c : text.toCharArray()) {
			if(Character.getType(c) == Character.LINE_SEPARATOR
			   || Character.getType(c) == Character.PARAGRAPH_SEPARATOR
			   || c == '\n' || c == '\r')
				return false;
		}
		
		return true;
	}

	/**
	 * Check for any values in the string that are not valid Unicode
	 * characters.
	 */
	public static boolean containsNoInvalidCharacters(String text) {
		for (int i = 0; i < text.length(); ) {
			int c = text.codePointAt(i);
			i += Character.charCount(c);

			if ((c & 0xFFFE) == 0xFFFE
				|| Character.getType(c) == Character.SURROGATE)
				return false;
		}

		return true;
	}

	/**
	 * Check for any control characters (including tab, LF, and CR) in
	 * the string.
	 */
	public static boolean containsNoControlCharacters(String text) {
		for(Character c : text.toCharArray()) {
			if(Character.getType(c) == Character.CONTROL)
				return false;
		}

		return true;
	}

	/**
	 * Check for any unpaired directional or annotation characters in
	 * the string, or any nested annotations.
	 */
	public static boolean containsNoInvalidFormatting(String text) {
		int dirCount = 0;
		boolean inAnnotatedText = false;
		boolean inAnnotation = false;

		for (Character c : text.toCharArray()) {
			if (c == 0x202A			// LEFT-TO-RIGHT EMBEDDING
				|| c == 0x202B		// RIGHT-TO-LEFT EMBEDDING
				|| c == 0x202D		// LEFT-TO-RIGHT OVERRIDE
				|| c == 0x202E) {	// RIGHT-TO-LEFT OVERRIDE
				dirCount++;
			}
			else if (c == 0x202C) {	// POP DIRECTIONAL FORMATTING
				dirCount--;
				if (dirCount < 0)
					return false;
			}
			else if (c == 0xFFF9) {	// INTERLINEAR ANNOTATION ANCHOR
				if (inAnnotatedText || inAnnotation)
					return false;
				inAnnotatedText = true;
			}
			else if (c == 0xFFFA) {	// INTERLINEAR ANNOTATION SEPARATOR
				if (!inAnnotatedText)
					return false;
				inAnnotatedText = false;
				inAnnotation = true;
			}
			else if (c == 0xFFFB) { // INTERLINEAR ANNOTATION TERMINATOR
				if (!inAnnotation)
					return false;
				inAnnotation = false;
			}
		}

		return (dirCount == 0 && !inAnnotatedText && !inAnnotation);
	}
	
	public static boolean isLatinLettersAndNumbersOnly(String text) {
		for(char c : text.toCharArray()) {
			if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= '0' && c <= '9')
				continue;
			else
				return false;
		}
		
		return true;
	}

}
