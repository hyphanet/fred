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
		charTable.put("quot", Character.valueOf((char)34));
		charTable.put("amp", Character.valueOf((char)38));
		charTable.put("apos", Character.valueOf((char)39));
		charTable.put("lt", Character.valueOf((char)60));
		charTable.put("gt", Character.valueOf((char)62));
		charTable.put("nbsp", Character.valueOf((char)160));
		charTable.put("iexcl", Character.valueOf((char)161));
		charTable.put("cent", Character.valueOf((char)162));
		charTable.put("pound", Character.valueOf((char)163));
		charTable.put("curren", Character.valueOf((char)164));
		charTable.put("yen", Character.valueOf((char)165));
		charTable.put("brvbar", Character.valueOf((char)166));
		charTable.put("sect", Character.valueOf((char)167));
		charTable.put("uml", Character.valueOf((char)168));
		charTable.put("copy", Character.valueOf((char)169));
		charTable.put("ordf", Character.valueOf((char)170));
		charTable.put("laquo", Character.valueOf((char)171));
		charTable.put("not", Character.valueOf((char)172));
		charTable.put("shy", Character.valueOf((char)173));
		charTable.put("reg", Character.valueOf((char)174));
		charTable.put("macr", Character.valueOf((char)175));
		charTable.put("deg", Character.valueOf((char)176));
		charTable.put("plusmn", Character.valueOf((char)177));
		charTable.put("sup2", Character.valueOf((char)178));
		charTable.put("sup3", Character.valueOf((char)179));
		charTable.put("acute", Character.valueOf((char)180));
		charTable.put("micro", Character.valueOf((char)181));
		charTable.put("para", Character.valueOf((char)182));
		charTable.put("middot", Character.valueOf((char)183));
		charTable.put("cedil", Character.valueOf((char)184));
		charTable.put("sup1", Character.valueOf((char)185));
		charTable.put("ordm", Character.valueOf((char)186));
		charTable.put("raquo", Character.valueOf((char)187));
		charTable.put("frac14", Character.valueOf((char)188));
		charTable.put("frac12", Character.valueOf((char)189));
		charTable.put("frac34", Character.valueOf((char)190));
		charTable.put("iquest", Character.valueOf((char)191));
		charTable.put("Agrave", Character.valueOf((char)192));
		charTable.put("Aacute", Character.valueOf((char)193));
		charTable.put("Acirc", Character.valueOf((char)194));
		charTable.put("Atilde", Character.valueOf((char)195));
		charTable.put("Auml", Character.valueOf((char)196));
		charTable.put("Aring", Character.valueOf((char)197));
		charTable.put("AElig", Character.valueOf((char)198));
		charTable.put("Ccedil", Character.valueOf((char)199));
		charTable.put("Egrave", Character.valueOf((char)200));
		charTable.put("Eacute", Character.valueOf((char)201));
		charTable.put("Ecirc", Character.valueOf((char)202));
		charTable.put("Euml", Character.valueOf((char)203));
		charTable.put("Igrave", Character.valueOf((char)204));
		charTable.put("Iacute", Character.valueOf((char)205));
		charTable.put("Icirc", Character.valueOf((char)206));
		charTable.put("Iuml", Character.valueOf((char)207));
		charTable.put("ETH", Character.valueOf((char)208));
		charTable.put("Ntilde", Character.valueOf((char)209));
		charTable.put("Ograve", Character.valueOf((char)210));
		charTable.put("Oacute", Character.valueOf((char)211));
		charTable.put("Ocirc", Character.valueOf((char)212));
		charTable.put("Otilde", Character.valueOf((char)213));
		charTable.put("Ouml", Character.valueOf((char)214));
		charTable.put("times", Character.valueOf((char)215));
		charTable.put("Oslash", Character.valueOf((char)216));
		charTable.put("Ugrave", Character.valueOf((char)217));
		charTable.put("Uacute", Character.valueOf((char)218));
		charTable.put("Ucirc", Character.valueOf((char)219));
		charTable.put("Uuml", Character.valueOf((char)220));
		charTable.put("Yacute", Character.valueOf((char)221));
		charTable.put("THORN", Character.valueOf((char)222));
		charTable.put("szlig", Character.valueOf((char)223));
		charTable.put("agrave", Character.valueOf((char)224));
		charTable.put("aacute", Character.valueOf((char)225));
		charTable.put("acirc", Character.valueOf((char)226));
		charTable.put("atilde", Character.valueOf((char)227));
		charTable.put("auml", Character.valueOf((char)228));
		charTable.put("aring", Character.valueOf((char)229));
		charTable.put("aelig", Character.valueOf((char)230));
		charTable.put("ccedil", Character.valueOf((char)231));
		charTable.put("egrave", Character.valueOf((char)232));
		charTable.put("eacute", Character.valueOf((char)233));
		charTable.put("ecirc", Character.valueOf((char)234));
		charTable.put("euml", Character.valueOf((char)235));
		charTable.put("igrave", Character.valueOf((char)236));
		charTable.put("iacute", Character.valueOf((char)237));
		charTable.put("icirc", Character.valueOf((char)238));
		charTable.put("iuml", Character.valueOf((char)239));
		charTable.put("eth", Character.valueOf((char)240));
		charTable.put("ntilde", Character.valueOf((char)241));
		charTable.put("ograve", Character.valueOf((char)242));
		charTable.put("oacute", Character.valueOf((char)243));
		charTable.put("ocirc", Character.valueOf((char)244));
		charTable.put("otilde", Character.valueOf((char)245));
		charTable.put("ouml", Character.valueOf((char)246));
		charTable.put("divide", Character.valueOf((char)247));
		charTable.put("oslash", Character.valueOf((char)248));
		charTable.put("ugrave", Character.valueOf((char)249));
		charTable.put("uacute", Character.valueOf((char)250));
		charTable.put("ucirc", Character.valueOf((char)251));
		charTable.put("uuml", Character.valueOf((char)252));
		charTable.put("yacute", Character.valueOf((char)253));
		charTable.put("thorn", Character.valueOf((char)254));
		charTable.put("yuml", Character.valueOf((char)255));
		charTable.put("OElig", Character.valueOf((char)338));
		charTable.put("oelig", Character.valueOf((char)339));
		charTable.put("Scaron", Character.valueOf((char)352));
		charTable.put("scaron", Character.valueOf((char)353));
		charTable.put("Yuml", Character.valueOf((char)376));
		charTable.put("fnof", Character.valueOf((char)402));
		charTable.put("circ", Character.valueOf((char)710));
		charTable.put("tilde", Character.valueOf((char)732));
		charTable.put("Alpha", Character.valueOf((char)913));
		charTable.put("Beta", Character.valueOf((char)914));
		charTable.put("Gamma", Character.valueOf((char)915));
		charTable.put("Delta", Character.valueOf((char)916));
		charTable.put("Epsilon", Character.valueOf((char)917));
		charTable.put("Zeta", Character.valueOf((char)918));
		charTable.put("Eta", Character.valueOf((char)919));
		charTable.put("Theta", Character.valueOf((char)920));
		charTable.put("Iota", Character.valueOf((char)921));
		charTable.put("Kappa", Character.valueOf((char)922));
		charTable.put("Lambda", Character.valueOf((char)923));
		charTable.put("Mu", Character.valueOf((char)924));
		charTable.put("Nu", Character.valueOf((char)925));
		charTable.put("Xi", Character.valueOf((char)926));
		charTable.put("Omicron", Character.valueOf((char)927));
		charTable.put("Pi", Character.valueOf((char)928));
		charTable.put("Rho", Character.valueOf((char)929));
		charTable.put("Sigma", Character.valueOf((char)931));
		charTable.put("Tau", Character.valueOf((char)932));
		charTable.put("Upsilon", Character.valueOf((char)933));
		charTable.put("Phi", Character.valueOf((char)934));
		charTable.put("Chi", Character.valueOf((char)935));
		charTable.put("Psi", Character.valueOf((char)936));
		charTable.put("Omega", Character.valueOf((char)937));
		charTable.put("alpha", Character.valueOf((char)945));
		charTable.put("beta", Character.valueOf((char)946));
		charTable.put("gamma", Character.valueOf((char)947));
		charTable.put("delta", Character.valueOf((char)948));
		charTable.put("epsilon", Character.valueOf((char)949));
		charTable.put("zeta", Character.valueOf((char)950));
		charTable.put("eta", Character.valueOf((char)951));
		charTable.put("theta", Character.valueOf((char)952));
		charTable.put("iota", Character.valueOf((char)953));
		charTable.put("kappa", Character.valueOf((char)954));
		charTable.put("lambda", Character.valueOf((char)955));
		charTable.put("mu", Character.valueOf((char)956));
		charTable.put("nu", Character.valueOf((char)957));
		charTable.put("xi", Character.valueOf((char)958));
		charTable.put("omicron", Character.valueOf((char)959));
		charTable.put("pi", Character.valueOf((char)960));
		charTable.put("rho", Character.valueOf((char)961));
		charTable.put("sigmaf", Character.valueOf((char)962));
		charTable.put("sigma", Character.valueOf((char)963));
		charTable.put("tau", Character.valueOf((char)964));
		charTable.put("upsilon", Character.valueOf((char)965));
		charTable.put("phi", Character.valueOf((char)966));
		charTable.put("chi", Character.valueOf((char)967));
		charTable.put("psi", Character.valueOf((char)968));
		charTable.put("omega", Character.valueOf((char)969));
		charTable.put("thetasym", Character.valueOf((char)977));
		charTable.put("upsih", Character.valueOf((char)978));
		charTable.put("piv", Character.valueOf((char)982));
		charTable.put("ensp", Character.valueOf((char)8194));
		charTable.put("emsp", Character.valueOf((char)8195));
		charTable.put("thinsp", Character.valueOf((char)8201));
		charTable.put("zwnj", Character.valueOf((char)8204));
		charTable.put("zwj", Character.valueOf((char)8205));
		charTable.put("lrm", Character.valueOf((char)8206));
		charTable.put("rlm", Character.valueOf((char)8207));
		charTable.put("ndash", Character.valueOf((char)8211));
		charTable.put("mdash", Character.valueOf((char)8212));
		charTable.put("lsquo", Character.valueOf((char)8216));
		charTable.put("rsquo", Character.valueOf((char)8217));
		charTable.put("sbquo", Character.valueOf((char)8218));
		charTable.put("ldquo", Character.valueOf((char)8220));
		charTable.put("rdquo", Character.valueOf((char)8221));
		charTable.put("bdquo", Character.valueOf((char)8222));
		charTable.put("dagger", Character.valueOf((char)8224));
		charTable.put("Dagger", Character.valueOf((char)8225));
		charTable.put("bull", Character.valueOf((char)8226));
		charTable.put("hellip", Character.valueOf((char)8230));
		charTable.put("permil", Character.valueOf((char)8240));
		charTable.put("prime", Character.valueOf((char)8242));
		charTable.put("Prime", Character.valueOf((char)8243));
		charTable.put("lsaquo", Character.valueOf((char)8249));
		charTable.put("rsaquo", Character.valueOf((char)8250));
		charTable.put("oline", Character.valueOf((char)8254));
		charTable.put("frasl", Character.valueOf((char)8260));
		charTable.put("euro", Character.valueOf((char)8364));
		charTable.put("image", Character.valueOf((char)8465));
		charTable.put("weierp", Character.valueOf((char)8472));
		charTable.put("real", Character.valueOf((char)8476));
		charTable.put("trade", Character.valueOf((char)8482));
		charTable.put("alefsym", Character.valueOf((char)8501));
		charTable.put("larr", Character.valueOf((char)8592));
		charTable.put("uarr", Character.valueOf((char)8593));
		charTable.put("rarr", Character.valueOf((char)8594));
		charTable.put("darr", Character.valueOf((char)8595));
		charTable.put("harr", Character.valueOf((char)8596));
		charTable.put("crarr", Character.valueOf((char)8629));
		charTable.put("lArr", Character.valueOf((char)8656));
		charTable.put("uArr", Character.valueOf((char)8657));
		charTable.put("rArr", Character.valueOf((char)8658));
		charTable.put("dArr", Character.valueOf((char)8659));
		charTable.put("hArr", Character.valueOf((char)8660));
		charTable.put("forall", Character.valueOf((char)8704));
		charTable.put("part", Character.valueOf((char)8706));
		charTable.put("exist", Character.valueOf((char)8707));
		charTable.put("empty", Character.valueOf((char)8709));
		charTable.put("nabla", Character.valueOf((char)8711));
		charTable.put("isin", Character.valueOf((char)8712));
		charTable.put("notin", Character.valueOf((char)8713));
		charTable.put("ni", Character.valueOf((char)8715));
		charTable.put("prod", Character.valueOf((char)8719));
		charTable.put("sum", Character.valueOf((char)8721));
		charTable.put("minus", Character.valueOf((char)8722));
		charTable.put("lowast", Character.valueOf((char)8727));
		charTable.put("radic", Character.valueOf((char)8730));
		charTable.put("prop", Character.valueOf((char)8733));
		charTable.put("infin", Character.valueOf((char)8734));
		charTable.put("ang", Character.valueOf((char)8736));
		charTable.put("and", Character.valueOf((char)8743));
		charTable.put("or", Character.valueOf((char)8744));
		charTable.put("cap", Character.valueOf((char)8745));
		charTable.put("cup", Character.valueOf((char)8746));
		charTable.put("int", Character.valueOf((char)8747));
		charTable.put("there4", Character.valueOf((char)8756));
		charTable.put("sim", Character.valueOf((char)8764));
		charTable.put("cong", Character.valueOf((char)8773));
		charTable.put("asymp", Character.valueOf((char)8776));
		charTable.put("ne", Character.valueOf((char)8800));
		charTable.put("equiv", Character.valueOf((char)8801));
		charTable.put("le", Character.valueOf((char)8804));
		charTable.put("ge", Character.valueOf((char)8805));
		charTable.put("sub", Character.valueOf((char)8834));
		charTable.put("sup", Character.valueOf((char)8835));
		charTable.put("nsub", Character.valueOf((char)8836));
		charTable.put("sube", Character.valueOf((char)8838));
		charTable.put("supe", Character.valueOf((char)8839));
		charTable.put("oplus", Character.valueOf((char)8853));
		charTable.put("otimes", Character.valueOf((char)8855));
		charTable.put("perp", Character.valueOf((char)8869));
		charTable.put("sdot", Character.valueOf((char)8901));
		charTable.put("lceil", Character.valueOf((char)8968));
		charTable.put("rceil", Character.valueOf((char)8969));
		charTable.put("lfloor", Character.valueOf((char)8970));
		charTable.put("rfloor", Character.valueOf((char)8971));
		charTable.put("lang", Character.valueOf((char)9001));
		charTable.put("rang", Character.valueOf((char)9002));
		charTable.put("loz", Character.valueOf((char)9674));
		charTable.put("spades", Character.valueOf((char)9824));
		charTable.put("clubs", Character.valueOf((char)9827));
		charTable.put("hearts", Character.valueOf((char)9829));
		charTable.put("diams", Character.valueOf((char)9830));
	}
}
