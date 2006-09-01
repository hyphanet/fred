package freenet.clients.http.filter;
import java.io.*;
import java.util.*;
/* This class tokenizes a CSS2 Reader stream, writes it out to the output Writer, and filters any URLs found */
// WARNING: this is not as thorough as the HTML parser - new versions of the standard could lead to anonymity risks. See comments in SaferFilter.java
// Mostly from http://www.w3.org/TR/REC-CSS2/grammar.html

%%

%{
	// Put stuff to include in the class here
	String detectedCharset;

	// External flag
	boolean paranoidStringCheck = false;
	boolean deleteErrors = true;
	boolean debug = true;
	
	// Internal flags
	boolean postBadImportFlag = false; // both URLs and @import's
	boolean importFlag = false;
	boolean urlFlag = false;

	// Writer
	Writer w = null; // Will NPE if not initialized properly

	public void parse () throws IOException {
		while (yylex() != null);
	}
	
	CSSTokenizerFilter(Reader r, Writer w, boolean paranoidStringCheck) {
		this(r);
		this.w = w;
		this.paranoidStringCheck = paranoidStringCheck;
	}

	void throwError(String s) throws IOException {
		throw new IllegalStateException("You MUST override throwError!");
	}

	String processImportURL(String s) {
		throw new IllegalStateException("You MUST override processImportURL!");
	}

	String processURL(String s) {
		throw new IllegalStateException("You MUST override processURL!");
	}
	
	void log(String s) {
		System.err.println("CSSTokenizerFilter: "+s);
	}

	void logError(String s) {
		System.err.println("CSSTokenizerFilter ERROR: "+s);
	}
	
	static String unquote(String s, char c) {
		if(s.length() > 1) {
			if(s.charAt(s.length()-1) == c) {
				s = s.substring(1, s.length()-1);
				return s;
			} else return "";
		} else return "";
	}
	
	// This is not very efficient. The parser below knows the quoting rules too.
	
	static boolean isHexDigit(char c) {
		return ('a' <= c && c <= 'f' ||
		  	'A' <= c && c <= 'F' ||
			'0' <= c && c <= '9');
	}
	
	class DecodedStringThingy {
		char quote; // " " means not quoted
		boolean url; // in a url() ?
		String data;
		public String suffix; // includes any whitespace
		public DecodedStringThingy(String s) {
			if(s.startsWith("url(")) {
				s = s.substring("url(".length());
				url = true;
			}
			char q = s.charAt(0);
			if(q == '\'' || q == '\"') {
				quote = q;
				s = s.substring(1);
			} else quote = ' ';
			StringBuffer buffer = new StringBuffer();
			int x = 0;
			boolean justEscaping = false;
			boolean stillEscaping = false;
			StringBuffer hexEscape = new StringBuffer();
			while(x < s.length()-1) {
				char c = s.charAt(x);
				x++;
				if(justEscaping) {
					if(c == '\n') {
						buffer.append(c);
						justEscaping = false;
					} else if(isHexDigit(c)) {
						hexEscape.append(c);
						justEscaping = false;
						stillEscaping = true;
					} else {
						buffer.append(c);
						// Will need to be reencoded if quote or \n
						justEscaping = false;
					}
				} else if(stillEscaping) {
					if(isHexDigit(c) && hexEscape.length() < 6) {
						hexEscape.append(c);
					} else if(Character.isWhitespace(c)) {
						// Ignore one whitespace char after an escape
						int d = Integer.parseInt(hexEscape.toString(),
									 16);
						if(d > 0xFFFF) {
							String error = 
							    "UCS-4 CHARACTERS OVER 0xFFFF NOT SUPPORTED!";
							logError(error);
							try {
								w.write("/* "+error+"*/");
							} catch (IOException e) {};
						} else {
							c = (char)d;
							buffer.append(c);
						}
						stillEscaping = false;
						hexEscape = new StringBuffer();
					} else {
						int d = Integer.parseInt(hexEscape.toString(),
									 16);
						if(d > 0xFFFF) {
							String error = 
							    "UCS-4 CHARACTERS OVER 0xFFFF NOT SUPPORTED!";
							logError(error);
							try {
								w.write("/* "+error+"*/");
							} catch (IOException e) {};
						} else {
							char o = (char)d;
							buffer.append(o);
						}
						buffer.append(c);
						stillEscaping = false;
						hexEscape = new StringBuffer();
					}
				} else {
					if(quote != ' ' && c == quote) {
						break;
					} else if (c == '\\') {
						justEscaping = true;
					} else {
						buffer.append(c);
					}
				}
			}
			data = buffer.toString();
			if(s.length() > (x+1))
				suffix = s.substring(x+1);
			else suffix = "";
		}
		
		public String toString() {
			StringBuffer out = new StringBuffer();
			if(url) out.append("url(");
			if(quote != ' ') out.append(quote);
			out.append(unescapeData());
			if(quote != ' ') out.append(quote);
			if(url) out.append(")");
			out.append(suffix);
			return out.toString();
		}
		
		public String unescapeData() {
			StringBuffer sb = new StringBuffer();
			for(int i=0;i<data.length();i++) {
				char c = data.charAt(i);
				if(c == quote || c == '\n') {
					sb.append('\\');
				}
				sb.append(c);
			}
			return sb.toString();
		}
	}
%}

%class CSSTokenizerFilter
%unicode
%ignorecase

// Case sensitivity DOES NOT AFFECT CHARACTER CLASSES!
H=[0-9a-fA-F]
NONASCII=[\200-\4177777]
UNICODE=\\{H}{1,6}[ \t\r\n\f]?
ESCAPE={UNICODE}|\\[ -~\200-\4177777]
NMSTART=[a-zA-Z]|{NONASCII}|{ESCAPE}
NMCHAR=[a-zA-Z0-9-]|{NONASCII}|{ESCAPE}

// The spec (http://www.w3.org/TR/REC-CSS2/grammar.html, mostly D.2 for this bit)
// is on crack wrt string/url, so this is guesswork
STRING1=\"(\\{NL}|\'|\\\"|{NONASCII}|{ESCAPE}|[^\"])*\"
STRING2=\'(\\{NL}|\"|\\\'|{NONASCII}|{ESCAPE}|[^\'])*\'

IDENT={NMSTART}{NMCHAR}*
NAME={NMCHAR}+
NUM=[0-9]+|[0-9]*"."[0-9]+
STRING={STRING1}|{STRING2}
INBRACKET=([^\)]|"\\)"|STRING)*

// See comments for STRING1/STRING2 :)
URL=([^\(\)\"\']|{NONASCII}|{ESCAPE})*

W=[ \t\r\n\f]*
NL=\n|\r\n|\r|\f
RANGE=\?{1,6}|{H}(\?{0,5}|{H}(\?{0,4}|{H}(\?{0,3}|{H}(\?{0,2}|{H}(\??|{H})))))
HEXCOLOR="#"(({H}{H}{H})|({H}{H}{H}{H}{H}{H}))

// From grammer
MEDIUM={IDENT}{W}*
// As distinct from MEDIA, which allows rulesets
MEDIUMS={MEDIUM}(","{W}*{MEDIUM})*

// This is rather incomprehensible, so I am adding log messages for every token. They will not actually call log() unless debug is true.

// Loosly based on http://www.w3.org/TR/REC-CSS2/grammar.html
%%

{HEXCOLOR} {
	String s = yytext();
	if(debug) log("Got hexcolor: "+s);
	w.write(s);
}
"url("{W}*({STRING}|{URL}){W}")" {
	// This is horrible. However it seems that there is no other way to do it with either jflex or CUP, as {URL} cannot be an unambiguous token :(
	String s = yytext();
	if(debug) log("Recognized URL: "+s);
	
	DecodedStringThingy dst = new DecodedStringThingy(s);
	
	if(!dst.url) {
		throw new IllegalStateException("parsing url().. isn't a url()");
	}
	if(dst.suffix.length() > 0) {
		yypushback(dst.suffix.length());
		dst.suffix = "";
	}
	
	s = dst.data;
	if(debug) log("URL now: "+s);
	s = processURL(s);
	dst.data = s;
	if(s == null || s.equals("")) {
		if(debug) log("URL invalid");
		w.write("url()");
	} else {
		s = dst.toString();
		if(debug) log("Writing: "+s);
		w.write(s);
	}
}
"@import"{W}{W}*({STRING}|{URL})({W}*{W}{MEDIUMS})?";" {
	String s = yytext();
	if(debug) log("Found @import: "+s);
	s = s.substring("@import".length());
	s = s.trim();
	DecodedStringThingy dst = new DecodedStringThingy(s);
	s = dst.data;
	if(debug) log("URL: "+s);
	s = processURL(s);
	if (!(s == null || s.equals(""))) {
		if(debug) log("URL now: "+s);
		s = "@import "+dst.toString();
		if(debug) log("Writing: "+s);
		w.write(s);
	} else
		if(debug) log("Dropped @import");
}
{W}"{"{W} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched open braces: "+s);
}
{W}"}"{W} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched close braces: "+s);
}
[ \t\r\n\f]+	{
	String s = yytext();
	w.write(s);
	if(debug) log("Matched whitespace: "+s);
}
//"/*"([^*]|[\r\n]|("*"+([^*/]|[\r\n])))*"*"*"/" {
"/*" ~"*/" {
	String s = yytext();
	StringBuffer sb = new StringBuffer(s.length());
	sb.append("/* comment: ");
	boolean inPrefix = true;
	for(int i=2;i<s.length()-2;i++) {
		char c = s.charAt(i);
		if(inPrefix && Character.isWhitespace(c)) {
			continue;
		}
		inPrefix = false;
		if(Character.isDigit(c) || Character.isWhitespace(c) ||
			Character.isLetter(c) || c == '.' || c == '_' || c == '-') {
			// No @, no !, etc; IE has been known to do things with comments
			// in CSS, and other browsers may too
			sb.append(c);
		}
	}
	while(Character.isWhitespace(sb.charAt(sb.length()-1)))
		sb.deleteCharAt(sb.length()-1);
	sb.append(" */");
	w.write(sb.toString());
	if(debug) log("Matched comment: "+s+" -> "+sb.toString());
}
"<!--" { 
	String s = yytext();
	w.write(s);
	if(debug) log("Matched HTML comment: "+s);
}
"-->" {
	String s = yytext();
	w.write(s); 
	if(debug) log("Matched HTML comment: "+s);
}
"~=" { 
	String s = yytext();
	w.write(s); 
	if(debug) log("Matched ~=: "+s);
}
"|=" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched |=: "+s);
}
{IDENT} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched ident: "+s);
}
"@page" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched @page: "+s);
}
"@media" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched @media: "+s);
}
"@font-face" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched @font-face: "+s);
}
"@"{IDENT}[^;\}\"]*[;\}] {
	if(!deleteErrors) {
		throwError("Unknown @identifier "+yytext());
	} else {
		String s = yytext();
		if(debug) log("Discarded identifier: "+s);
		// Ignore
	}
}
"#"{NAME} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched #name: "+s);
}
"!{W}important" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched important: "+s);
}
U\+{RANGE} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched unicode: "+s);
}
U\+{H}{1,6}-{H}{1,6} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched unicode range: "+s);
}
{NUM}("em"|"ex"|"px"|"cm"|"mm"|"in"|"pc"|"deg"|"rad"|"grad"|"ms"|"s"|"Hz"|"kHz"|"%") {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched measurement: "+s);
}
{NUM} {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched number: "+s);
}

{MEDIUMS}{W}*";" {
	if(postBadImportFlag) {
		// Ignore
		postBadImportFlag = false;
		if(debug) log("Ignoring mediums list because after bad import: "+
			yytext());
	} else {
		String s = yytext();
		w.write(s);
		if(debug) log("Matched and passing on mediums list: "+s);
	}
}

"@charset"{W}*{STRING}{W}*";" {
	String s = yytext();
	detectedCharset = s;
	if(debug) log("Matched and ignoring charset: "+s);
	// Ignore
}
"url("{INBRACKET}")" {
	String s = yytext();
	if(debug) log("Ignoring unrecognizable url: "+s);
	w.write("/* Ignoring unmatchable URL */url()");
}
{IDENT}"(" {
	String s = yytext();
	if(s.startsWith("url")) throwError("Invalid contents of url()");
	w.write(s);
	if(debug) log("Matched function start: "+s);
}
")" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched function end: "+s);
}
";" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched semicolon: "+s);
}
{STRING} {
	String s = yytext();
	if(debug) log("Matched string: "+s);
	if(paranoidStringCheck && s.indexOf(':') != -1) {
		w.write("/* Deleted disallowed string */");
		log("Deleted disallowed string: "+s);
	} else {
		w.write(s);
	}
}
// These are plain chars, which would be passed through as tokens somehow by the spec'd tokenizer
","|":"|"/"|">"|"-"|"+"|"."|"*" {
	String s = yytext();
	w.write(s);
	if(debug) log("Matched single char: "+s);
}
. {
	String s = yytext();
	char c = s.charAt(0);
	log("Matched anything: "+yytext()+" - ignoring");
	w.write("/* ignored unmatched char: "+c+" */"); // single char cannot break out of comment
}
