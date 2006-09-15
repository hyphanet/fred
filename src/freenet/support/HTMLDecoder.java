/*
  HTMLDecoder.java / Freenet
  Copyright (C) Yves Lempereur (avian)
  Copyright (C) 2005-2006 The Free Network project
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.support;

import java.util.HashMap;

/**
 * Description: Utility for converting character references e.g.: &lt; &gt;
 * &quot; &#229; &#1048; &#x6C34;
 * 
 * @author Yves Lempereur (avian)
 */
public class HTMLDecoder {

	public static final HashMap charTable;

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
					if (d == '#') {
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
		return (ch == '\u0020')
			|| (ch == '\r')
			|| (ch == '\n')
			|| (ch == '\u0009')
			|| (ch == '\u000c')
			|| (ch == '\u200b');
	}

	static {
		charTable = new HashMap();
		charTable.put("quot", new Character((char)34));
		charTable.put("amp", new Character((char)38));
		charTable.put("apos", new Character((char)39));
		charTable.put("lt", new Character((char)60));
		charTable.put("gt", new Character((char)62));
		charTable.put("nbsp", new Character((char)160));
		charTable.put("iexcl", new Character((char)161));
		charTable.put("cent", new Character((char)162));
		charTable.put("pound", new Character((char)163));
		charTable.put("curren", new Character((char)164));
		charTable.put("yen", new Character((char)165));
		charTable.put("brvbar", new Character((char)166));
		charTable.put("sect", new Character((char)167));
		charTable.put("uml", new Character((char)168));
		charTable.put("copy", new Character((char)169));
		charTable.put("ordf", new Character((char)170));
		charTable.put("laquo", new Character((char)171));
		charTable.put("not", new Character((char)172));
		charTable.put("shy", new Character((char)173));
		charTable.put("reg", new Character((char)174));
		charTable.put("macr", new Character((char)175));
		charTable.put("deg", new Character((char)176));
		charTable.put("plusmn", new Character((char)177));
		charTable.put("sup2", new Character((char)178));
		charTable.put("sup3", new Character((char)179));
		charTable.put("acute", new Character((char)180));
		charTable.put("micro", new Character((char)181));
		charTable.put("para", new Character((char)182));
		charTable.put("middot", new Character((char)183));
		charTable.put("cedil", new Character((char)184));
		charTable.put("sup1", new Character((char)185));
		charTable.put("ordm", new Character((char)186));
		charTable.put("raquo", new Character((char)187));
		charTable.put("frac14", new Character((char)188));
		charTable.put("frac12", new Character((char)189));
		charTable.put("frac34", new Character((char)190));
		charTable.put("iquest", new Character((char)191));
		charTable.put("Agrave", new Character((char)192));
		charTable.put("Aacute", new Character((char)193));
		charTable.put("Acirc", new Character((char)194));
		charTable.put("Atilde", new Character((char)195));
		charTable.put("Auml", new Character((char)196));
		charTable.put("Aring", new Character((char)197));
		charTable.put("AElig", new Character((char)198));
		charTable.put("Ccedil", new Character((char)199));
		charTable.put("Egrave", new Character((char)200));
		charTable.put("Eacute", new Character((char)201));
		charTable.put("Ecirc", new Character((char)202));
		charTable.put("Euml", new Character((char)203));
		charTable.put("Igrave", new Character((char)204));
		charTable.put("Iacute", new Character((char)205));
		charTable.put("Icirc", new Character((char)206));
		charTable.put("Iuml", new Character((char)207));
		charTable.put("ETH", new Character((char)208));
		charTable.put("Ntilde", new Character((char)209));
		charTable.put("Ograve", new Character((char)210));
		charTable.put("Oacute", new Character((char)211));
		charTable.put("Ocirc", new Character((char)212));
		charTable.put("Otilde", new Character((char)213));
		charTable.put("Ouml", new Character((char)214));
		charTable.put("times", new Character((char)215));
		charTable.put("Oslash", new Character((char)216));
		charTable.put("Ugrave", new Character((char)217));
		charTable.put("Uacute", new Character((char)218));
		charTable.put("Ucirc", new Character((char)219));
		charTable.put("Uuml", new Character((char)220));
		charTable.put("Yacute", new Character((char)221));
		charTable.put("THORN", new Character((char)222));
		charTable.put("szlig", new Character((char)223));
		charTable.put("agrave", new Character((char)224));
		charTable.put("aacute", new Character((char)225));
		charTable.put("acirc", new Character((char)226));
		charTable.put("atilde", new Character((char)227));
		charTable.put("auml", new Character((char)228));
		charTable.put("aring", new Character((char)229));
		charTable.put("aelig", new Character((char)230));
		charTable.put("ccedil", new Character((char)231));
		charTable.put("egrave", new Character((char)232));
		charTable.put("eacute", new Character((char)233));
		charTable.put("ecirc", new Character((char)234));
		charTable.put("euml", new Character((char)235));
		charTable.put("igrave", new Character((char)236));
		charTable.put("iacute", new Character((char)237));
		charTable.put("icirc", new Character((char)238));
		charTable.put("iuml", new Character((char)239));
		charTable.put("eth", new Character((char)240));
		charTable.put("ntilde", new Character((char)241));
		charTable.put("ograve", new Character((char)242));
		charTable.put("oacute", new Character((char)243));
		charTable.put("ocirc", new Character((char)244));
		charTable.put("otilde", new Character((char)245));
		charTable.put("ouml", new Character((char)246));
		charTable.put("divide", new Character((char)247));
		charTable.put("oslash", new Character((char)248));
		charTable.put("ugrave", new Character((char)249));
		charTable.put("uacute", new Character((char)250));
		charTable.put("ucirc", new Character((char)251));
		charTable.put("uuml", new Character((char)252));
		charTable.put("yacute", new Character((char)253));
		charTable.put("thorn", new Character((char)254));
		charTable.put("yuml", new Character((char)255));
		charTable.put("OElig", new Character((char)338));
		charTable.put("oelig", new Character((char)339));
		charTable.put("Scaron", new Character((char)352));
		charTable.put("scaron", new Character((char)353));
		charTable.put("Yuml", new Character((char)376));
		charTable.put("fnof", new Character((char)402));
		charTable.put("circ", new Character((char)710));
		charTable.put("tilde", new Character((char)732));
		charTable.put("Alpha", new Character((char)913));
		charTable.put("Beta", new Character((char)914));
		charTable.put("Gamma", new Character((char)915));
		charTable.put("Delta", new Character((char)916));
		charTable.put("Epsilon", new Character((char)917));
		charTable.put("Zeta", new Character((char)918));
		charTable.put("Eta", new Character((char)919));
		charTable.put("Theta", new Character((char)920));
		charTable.put("Iota", new Character((char)921));
		charTable.put("Kappa", new Character((char)922));
		charTable.put("Lambda", new Character((char)923));
		charTable.put("Mu", new Character((char)924));
		charTable.put("Nu", new Character((char)925));
		charTable.put("Xi", new Character((char)926));
		charTable.put("Omicron", new Character((char)927));
		charTable.put("Pi", new Character((char)928));
		charTable.put("Rho", new Character((char)929));
		charTable.put("Sigma", new Character((char)931));
		charTable.put("Tau", new Character((char)932));
		charTable.put("Upsilon", new Character((char)933));
		charTable.put("Phi", new Character((char)934));
		charTable.put("Chi", new Character((char)935));
		charTable.put("Psi", new Character((char)936));
		charTable.put("Omega", new Character((char)937));
		charTable.put("alpha", new Character((char)945));
		charTable.put("beta", new Character((char)946));
		charTable.put("gamma", new Character((char)947));
		charTable.put("delta", new Character((char)948));
		charTable.put("epsilon", new Character((char)949));
		charTable.put("zeta", new Character((char)950));
		charTable.put("eta", new Character((char)951));
		charTable.put("theta", new Character((char)952));
		charTable.put("iota", new Character((char)953));
		charTable.put("kappa", new Character((char)954));
		charTable.put("lambda", new Character((char)955));
		charTable.put("mu", new Character((char)956));
		charTable.put("nu", new Character((char)957));
		charTable.put("xi", new Character((char)958));
		charTable.put("omicron", new Character((char)959));
		charTable.put("pi", new Character((char)960));
		charTable.put("rho", new Character((char)961));
		charTable.put("sigmaf", new Character((char)962));
		charTable.put("sigma", new Character((char)963));
		charTable.put("tau", new Character((char)964));
		charTable.put("upsilon", new Character((char)965));
		charTable.put("phi", new Character((char)966));
		charTable.put("chi", new Character((char)967));
		charTable.put("psi", new Character((char)968));
		charTable.put("omega", new Character((char)969));
		charTable.put("thetasym", new Character((char)977));
		charTable.put("upsih", new Character((char)978));
		charTable.put("piv", new Character((char)982));
		charTable.put("ensp", new Character((char)8194));
		charTable.put("emsp", new Character((char)8195));
		charTable.put("thinsp", new Character((char)8201));
		charTable.put("zwnj", new Character((char)8204));
		charTable.put("zwj", new Character((char)8205));
		charTable.put("lrm", new Character((char)8206));
		charTable.put("rlm", new Character((char)8207));
		charTable.put("ndash", new Character((char)8211));
		charTable.put("mdash", new Character((char)8212));
		charTable.put("lsquo", new Character((char)8216));
		charTable.put("rsquo", new Character((char)8217));
		charTable.put("sbquo", new Character((char)8218));
		charTable.put("ldquo", new Character((char)8220));
		charTable.put("rdquo", new Character((char)8221));
		charTable.put("bdquo", new Character((char)8222));
		charTable.put("dagger", new Character((char)8224));
		charTable.put("Dagger", new Character((char)8225));
		charTable.put("bull", new Character((char)8226));
		charTable.put("hellip", new Character((char)8230));
		charTable.put("permil", new Character((char)8240));
		charTable.put("prime", new Character((char)8242));
		charTable.put("Prime", new Character((char)8243));
		charTable.put("lsaquo", new Character((char)8249));
		charTable.put("rsaquo", new Character((char)8250));
		charTable.put("oline", new Character((char)8254));
		charTable.put("frasl", new Character((char)8260));
		charTable.put("euro", new Character((char)8364));
		charTable.put("image", new Character((char)8465));
		charTable.put("weierp", new Character((char)8472));
		charTable.put("real", new Character((char)8476));
		charTable.put("trade", new Character((char)8482));
		charTable.put("alefsym", new Character((char)8501));
		charTable.put("larr", new Character((char)8592));
		charTable.put("uarr", new Character((char)8593));
		charTable.put("rarr", new Character((char)8594));
		charTable.put("darr", new Character((char)8595));
		charTable.put("harr", new Character((char)8596));
		charTable.put("crarr", new Character((char)8629));
		charTable.put("lArr", new Character((char)8656));
		charTable.put("uArr", new Character((char)8657));
		charTable.put("rArr", new Character((char)8658));
		charTable.put("dArr", new Character((char)8659));
		charTable.put("hArr", new Character((char)8660));
		charTable.put("forall", new Character((char)8704));
		charTable.put("part", new Character((char)8706));
		charTable.put("exist", new Character((char)8707));
		charTable.put("empty", new Character((char)8709));
		charTable.put("nabla", new Character((char)8711));
		charTable.put("isin", new Character((char)8712));
		charTable.put("notin", new Character((char)8713));
		charTable.put("ni", new Character((char)8715));
		charTable.put("prod", new Character((char)8719));
		charTable.put("sum", new Character((char)8721));
		charTable.put("minus", new Character((char)8722));
		charTable.put("lowast", new Character((char)8727));
		charTable.put("radic", new Character((char)8730));
		charTable.put("prop", new Character((char)8733));
		charTable.put("infin", new Character((char)8734));
		charTable.put("ang", new Character((char)8736));
		charTable.put("and", new Character((char)8743));
		charTable.put("or", new Character((char)8744));
		charTable.put("cap", new Character((char)8745));
		charTable.put("cup", new Character((char)8746));
		charTable.put("int", new Character((char)8747));
		charTable.put("there4", new Character((char)8756));
		charTable.put("sim", new Character((char)8764));
		charTable.put("cong", new Character((char)8773));
		charTable.put("asymp", new Character((char)8776));
		charTable.put("ne", new Character((char)8800));
		charTable.put("equiv", new Character((char)8801));
		charTable.put("le", new Character((char)8804));
		charTable.put("ge", new Character((char)8805));
		charTable.put("sub", new Character((char)8834));
		charTable.put("sup", new Character((char)8835));
		charTable.put("nsub", new Character((char)8836));
		charTable.put("sube", new Character((char)8838));
		charTable.put("supe", new Character((char)8839));
		charTable.put("oplus", new Character((char)8853));
		charTable.put("otimes", new Character((char)8855));
		charTable.put("perp", new Character((char)8869));
		charTable.put("sdot", new Character((char)8901));
		charTable.put("lceil", new Character((char)8968));
		charTable.put("rceil", new Character((char)8969));
		charTable.put("lfloor", new Character((char)8970));
		charTable.put("rfloor", new Character((char)8971));
		charTable.put("lang", new Character((char)9001));
		charTable.put("rang", new Character((char)9002));
		charTable.put("loz", new Character((char)9674));
		charTable.put("spades", new Character((char)9824));
		charTable.put("clubs", new Character((char)9827));
		charTable.put("hearts", new Character((char)9829));
		charTable.put("diams", new Character((char)9830));
	}
}
