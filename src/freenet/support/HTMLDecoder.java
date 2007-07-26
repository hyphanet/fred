/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support;

//import java.util.HashMap;
import java.util.Map;

/**
 * Description: Utility for converting character references e.g.: &lt; &gt;
 * &quot; &#229; &#1048; &#x6C34;
 * 
 * @author Yves Lempereur (avian)
 */
public class HTMLDecoder {

	static Map charTable = HTMLEntities.decodeMap;
	
	public static String decode(String s) {
		String t;
		Character ch;
		int tmpPos, i;

		int maxPos = s.length();
		StringBuffer sb = new StringBuffer(maxPos);
		int curPos = 0;
		while (curPos < maxPos) {
			char c = s.charAt(curPos++);
			if (c == '&') {
				tmpPos = curPos;
				if (tmpPos < maxPos) {
					char d = s.charAt(tmpPos++);
					if (d == '#') { // REDFLAG: FIXME: We might want to prevent control characters from beeing created here...
						if (tmpPos < maxPos) {
							d = s.charAt(tmpPos++);
							if ((d == 'x') || (d == 'X')) {
								if (tmpPos < maxPos) {
									d = s.charAt(tmpPos++);
									if (isHexDigit(d)) {
										while (tmpPos < maxPos) {
											d = s.charAt(tmpPos++);
											if (!isHexDigit(d)) {
												if (d == ';') {
													t =
														s.substring(
															curPos + 2,
															tmpPos - 1);
													try {
														i =
															Integer.parseInt(
																t,
																16);
														if ((i >= 0)
															&& (i < 65536)) {
															c = (char) i;
															curPos = tmpPos;
														}
													} catch (NumberFormatException e) {
													}
												}
												break;
											}
										}
									}
								}
							} else if (isDigit(d)) {
								while (tmpPos < maxPos) {
									d = s.charAt(tmpPos++);
									if (!isDigit(d)) {
										if (d == ';') {
											t =
												s.substring(
													curPos + 1,
													tmpPos - 1);
											try {
												i = Integer.parseInt(t);
												if ((i >= 0) && (i < 65536)) {
													c = (char) i;
													curPos = tmpPos;
												}
											} catch (NumberFormatException e) {
											}
										}
										break;
									}
								}
							}
						}
					} else if (isLetter(d)) {
						while (tmpPos < maxPos) {
							d = s.charAt(tmpPos++);
							if (!isLetterOrDigit(d)) {
								if (d == ';') {
									t = s.substring(curPos, tmpPos - 1);
									ch = (Character) charTable.get(t);
									if (ch != null) {
										c = ch.charValue();
										curPos = tmpPos;
									}
								}
								break;
							}
						}
					}
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static boolean isLetterOrDigit(char c) {
		return isLetter(c) || isDigit(c);
	}

	private static boolean isHexDigit(char c) {
		return isHexLetter(c) || isDigit(c);
	}

	private static boolean isLetter(char c) {
		return ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'));
	}

	private static boolean isHexLetter(char c) {
		return ((c >= 'a') && (c <= 'f')) || ((c >= 'A') && (c <= 'F'));
	}

	private static boolean isDigit(char c) {
		return (c >= '0') && (c <= '9');
	}

	public static String compact(String s) {
		int maxPos = s.length();
		StringBuffer sb = new StringBuffer(maxPos);
		int curPos = 0;
		while (curPos < maxPos) {
			char c = s.charAt(curPos++);
			if (isWhitespace(c)) {
				while ((curPos < maxPos) && isWhitespace(s.charAt(curPos))) {
					curPos++;
                }
				c = '\u0020';
			}
			sb.append(c);
		}
		return sb.toString();
	}

	// HTML is very particular about what constitutes white space.
	public static boolean isWhitespace(char ch) {
		return 
			//space
		    (ch == '\u0020')
			//Mac newline
		    || (ch == '\r')
		    //Unix newline
			|| (ch == '\n')		
			//tab
			|| (ch == '\u0009')
			//Control
			|| (ch == '\u000c')
			//zero width space
			|| (ch == '\u200b');
	}

}
