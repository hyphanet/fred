/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */

package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.Vector;

import freenet.support.HTMLDecoder;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.NullWriter;

public class HTMLFilter implements ContentDataFilter, CharsetExtractor {

	private static boolean logMINOR;
	
	private static boolean deleteWierdStuff = true;
	private static boolean deleteErrors = true;

	public Bucket readFilter(Bucket bucket, BucketFactory bf, String charset, HashMap otherParams, FilterCallback cb) throws DataFilterException, IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "readFilter(): charset="+charset);
		InputStream strm = bucket.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(strm, 4096);
		Bucket temp = bf.makeBucket(bucket.size());
		OutputStream os = temp.getOutputStream();
		BufferedOutputStream bos = new BufferedOutputStream(os, 4096);
		Reader r;
		Writer w;
		try {
			r = new BufferedReader(new InputStreamReader(bis, charset), 4096);
			w = new BufferedWriter(new OutputStreamWriter(bos, charset), 4096);
		} catch (UnsupportedEncodingException e) {
			os.close();
			strm.close();
			HTMLNode explanation = new HTMLNode("p");
			explanation.addChild("b", "Unknown character set!");
			explanation.addChild("#", " The page you are about to display has an unknown character set. This means that we are not able to filter the page, and it may compromize your anonymity.");
			throw new DataFilterException("Warning: Unknown character set ("+charset+ ')', "Warning: Unknown character set ("+HTMLEncoder.encode(charset)+ ')',
					"<p><b>Unknown character set</b> The page you are about to display has an unknown character set. "+
					"This means that we are not able to filter the page, and it may compromize your anonymity.", explanation);
		}
		HTMLParseContext pc = new HTMLParseContext(r, w, charset, cb);
		pc.run(temp);
		r.close();
		w.close();
		os.close();
		bos.close();
		bis.close();
		strm.close();
		return temp;
	}
	
	public Bucket writeFilter(Bucket bucket, BucketFactory bf, String charset, HashMap otherParams, FilterCallback cb) throws DataFilterException, IOException {
		throw new UnsupportedOperationException();
	}
	
	public String getCharset(Bucket bucket, String parseCharset) throws DataFilterException, IOException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);		
		if(logMINOR) Logger.minor(this, "getCharset(): default="+parseCharset);
		InputStream strm = bucket.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(strm, 4096);
		Writer w = new NullWriter();
		Reader r;
		r = new BufferedReader(new InputStreamReader(bis, parseCharset), 4096);
		HTMLParseContext pc = new HTMLParseContext(r, w, null, new NullFilterCallback());
		try {
			pc.run(null);
		} catch (Throwable t) {
			// Ignore ALL errors
			if(logMINOR) Logger.minor(this, "Caught "+t+" trying to detect MIME type with "+parseCharset);
		}
		if(logMINOR) Logger.minor(this, "Returning charset "+pc.detectedCharset);
		return pc.detectedCharset;
	}

	class HTMLParseContext {
		Reader r;
		Writer w;
		String charset;
		String detectedCharset;
		final FilterCallback cb;

		HTMLParseContext(Reader r, Writer w, String charset, FilterCallback cb) {
			this.r = r;
			this.w = w;
			this.charset = charset;
			this.cb = cb;
		}

		Bucket run(Bucket temp) throws IOException, DataFilterException {

			/**
			 * TOKENIZE Modes:
			 * <p>0) in text transitions: '<' ->(1) 1) in tag, not in
			 * quotes/comment/whitespace transitions: whitespace -> (4) (save
			 * current element) '"' -> (2) '--' at beginning of tag -> (3) '>' ->
			 * process whole tag 2) in tag, in quotes transitions: '"' -> (1)
			 * '>' -> grumble about markup in quotes in tag might confuse older
			 * user-agents (stay in current state) 3) in tag, in comment
			 * transitions: '-->' -> save/ignore comment, go to (0) '<' or '>' ->
			 * grumble about markup in comments 4) in tag, in whitespace
			 * transitions: '"' -> (2) '>' -> save tag, (0) anything else not
			 * whitespace -> (1)
			 * </p>
			 */
			StringBuffer b = new StringBuffer(100);
			StringBuffer balt = new StringBuffer(4000);
			Vector splitTag = new Vector();
			String currentTag = null;
			char pprevC = 0;
			char prevC = 0;
			char c = 0;
			mode = INTEXT;

			while (true) {
				int x = r.read();
				if (x == -1) {
					switch (mode) {
						case INTEXT :
							saveText(b, currentTag, w, this);
							break;
						default :
							// Dump unfinished tag
							break;
					}
					break;
				} else {
					pprevC = prevC;
					prevC = c;
					c = (char) x;
					switch (mode) {
						case INTEXT :
							if (c == '<') {
								saveText(b, currentTag, w, this);
								b.setLength(0);
								balt.setLength(0);
								mode = INTAG;
							} else {
								b.append(c);
							}
							break;
						case INTAG :
							balt.append(c);
							if (HTMLDecoder.isWhitespace(c)) {
								splitTag.add(b.toString());
								mode = INTAGWHITESPACE;
								b.setLength(0);
							} else if ((c == '<') && Character.isWhitespace(balt.charAt(0))) {
								// Previous was an un-escaped < in a script.
								saveText(b, currentTag, w, this);

								balt.setLength(0);
								b.setLength(0);
								splitTag.clear();
							} else if (c == '>') {
								splitTag.add(b.toString());
								b.setLength(0);
								processTag(splitTag, w, this);
								currentTag = (String)splitTag.get(0);
								splitTag.clear();
								balt.setLength(0);
								mode = INTEXT;
							} else if (
								(b.length() == 2)
									&& (c == '-')
									&& (prevC == '-')
									&& (pprevC == '!')) {
								mode = INTAGCOMMENT;
								b.append(c);
							} else if (c == '"') {
								mode = INTAGQUOTES;
								b.append(c);
							} else if (c == '\'') {
								mode = INTAGSQUOTES;
								b.append(c);
							} else if (c == '/') { /* Probable end tag */
								currentTag = null; /* We didn't remember what was the last tag, so ... */
								b.append(c);
							} else {
								b.append(c);
							}
							break;
						case INTAGQUOTES :
							if (c == '"') {
								mode = INTAG;
								b.append(c); // Part of the element
							} else if (c == '>') {
								b.append("&gt;");
							} else if (c == '<') {
								b.append("&lt;");
							} else {
								b.append(c);
							}
							break;
						case INTAGSQUOTES :
							if (c == '\'') {
								mode = INTAG;
								b.append(c); // Part of the element
							} else if (c == '<') {
								b.append("&lt;");
							} else if (c == '>') {
								b.append("&gt;");
							} else {
								b.append(c);
							}
							break;
							/*
							 * Comments are often used to temporarily disable
							 * markup; I shall allow it. (avian) White space is
							 * not permitted between the markup declaration
							 * open delimiter ("
							 * <!") and the comment open delimiter ("--"), but
							 * is permitted between the comment close delimiter
							 * ("--") and the markup declaration close
							 * delimiter (">"). A common error is to include a
							 * string of hyphens ("---") within a comment.
							 * Authors should avoid putting two or more
							 * adjacent hyphens inside comments. However, the
							 * only browser that actually gets it right is IE
							 * (others either don't allow it or allow other
							 * chars as well). The only safe course of action
							 * is to allow any and all chars, but eat them.
							 * (avian)
							 */
						case INTAGCOMMENT :
							if ((b.length() >= 4) && (c == '-') && (prevC == '-')) {
								b.append(c);
								mode = INTAGCOMMENTCLOSING;
							} else
								b.append(c);
							break;
						case INTAGCOMMENTCLOSING :
							if (c == '>') {
								saveComment(b, w, this);
								b.setLength(0);
								mode = INTEXT;
							} else {
								b.append(c);
								if(c != '-')
									mode = INTAGCOMMENT;
							}
							break;
						case INTAGWHITESPACE :
							if (c == '"') {
								mode = INTAGQUOTES;
								b.append(c);
							} else if (c == '\'') {
								// e.g. <div align = 'center'> (avian)
								mode = INTAGSQUOTES;
								b.append(c);
							} else if (c == '>') {
								if (!killTag)
									processTag(splitTag, w, this);
								killTag = false;
								currentTag = (String)splitTag.get(0);
								splitTag.clear();
								b.setLength(0);
								balt.setLength(0);
								mode = INTEXT;
							} else if ((c == '<') && Character.isWhitespace(balt.charAt(0))) {
								// Previous was an un-escaped < in a script.
								saveText(balt, currentTag, w, this);
								balt.setLength(0);
								b.setLength(0);
								splitTag.clear();
								mode = INTAG;
							} else if (HTMLDecoder.isWhitespace(c)) {
								// More whitespace, what fun
							} else {
								mode = INTAG;
								b.append(c);
							}
					}
				}
			}
			return temp;
		}

		int mode;
		static final int INTEXT = 0;
		static final int INTAG = 1;
		static final int INTAGQUOTES = 2;
		static final int INTAGSQUOTES = 3;
		static final int INTAGCOMMENT = 4;
		static final int INTAGCOMMENTCLOSING = 5;
		static final int INTAGWHITESPACE = 6;
		boolean killTag = false; // just this one
		boolean writeStyleScriptWithTag = false; // just this one
		boolean expectingBadComment = false;
		// has to be set on or off explicitly by tags
		boolean inStyle = false; // has to be set on or off explicitly by tags
		boolean inScript = false; // has to be set on or off explicitly by tags
		boolean killText = false; // has to be set on or off explicitly by tags
		boolean killStyle = false;
		int styleScriptRecurseCount = 0;
		String currentStyleScriptChunk = "";
		StringBuffer writeAfterTag = new StringBuffer(1024);
	}


	void saveText(StringBuffer s, String tagName, Writer w, HTMLParseContext pc)
		throws IOException {

		if(logMINOR) Logger.minor(this, "Saving text: "+s.toString());
		if (pc.killText) {
			return;
		}

		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if((c < 32) && (c != '\n') && (c != '\r') ) {
				// Not a real character
				// STRONGLY suggests somebody is using a bogus charset.
				// This could be in order to break the filter.
				s.deleteCharAt(i);
			}
		}
		
		String style = s.toString();
		if (pc.inStyle || pc.inScript) {
			pc.currentStyleScriptChunk += style;
			return; // is parsed and written elsewhere
		}
		StringBuffer out = new StringBuffer(s.length()*2);
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(c == '<') {
				out.append("&lt;");
			} else {
				out.append(c);
			}
		}
		String sout = out.toString();
		if(pc.cb != null)
			pc.cb.onText(sout, tagName); /* Tag name is given as type for the text */
		
		w.write(sout);
	}

	void processTag(Vector splitTag, Writer w, HTMLParseContext pc)
		throws IOException, DataFilterException {
		// First, check that it is a recognized tag
		if(logMINOR) {
			for(int i=0;i<splitTag.size();i++)
				Logger.minor(this, "Tag["+i+"]="+splitTag.get(i));
		}
		ParsedTag t = new ParsedTag(splitTag);
		if (!pc.killTag) {
			t = t.sanitize(pc);
			if (t != null) {
				if (pc.writeStyleScriptWithTag) {
					pc.writeStyleScriptWithTag = false;
					String style = pc.currentStyleScriptChunk;
					if ((style == null) || (style.length() == 0))
						pc.writeAfterTag.append("<!-- deleted unknown style -->");
					else
						w.write(style);
					pc.currentStyleScriptChunk = "";
				}
				t.write(w);
				if (pc.writeAfterTag.length() > 0) {
					w.write(pc.writeAfterTag.toString());
					pc.writeAfterTag = new StringBuffer(1024);
				}
			} else
				pc.writeStyleScriptWithTag = false;
		} else {
			pc.killTag = false;
			pc.writeStyleScriptWithTag = false;
		}
	}

	void saveComment(StringBuffer s, Writer w, HTMLParseContext pc)
		throws IOException {
		if((s.length() > 3) && (s.charAt(0) == '!') && (s.charAt(1) == '-') && (s.charAt(2) == '-')) {
			s.delete(0, 3);
			if(s.charAt(s.length()-1) == '-')
				s.setLength(s.length()-1);
			if(s.charAt(s.length()-1) == '-')
				s.setLength(s.length()-1);
		}
		if(logMINOR) Logger.minor(this, "Saving comment: "+s.toString());
		if (pc.expectingBadComment)
			return; // ignore it

		if (pc.inStyle || pc.inScript) {
			pc.currentStyleScriptChunk += s;
			return; // </style> handler should write
		}
		if (pc.killTag) {
			pc.killTag = false;
			return;
		}
		StringBuffer sb = new StringBuffer();
		for(int i=0;i<s.length();i++) {
			char c = s.charAt(i);
			if(c == '<') {
				sb.append("&lt;");
			} else if(c == '>') {
				sb.append("&gt;");
			} else {
				sb.append(c);
			}
		}
		s = sb;
		w.write("<!-- ");
		w.write(s.toString());
		w.write(" -->");
	}

	static void throwFilterException(String s) throws DataFilterException {
		// FIXME
		throw new DataFilterException(s, s,
				"The HTML filter failed to parse the page: "+s,
				new HTMLNode("div", "The HTML filter failed to parse the page: " + s));
	}

	static class ParsedTag {
		final String element;
		final String[] unparsedAttrs;
		final boolean startSlash;
		final boolean endSlash;
		/*
		 * public ParsedTag(ParsedTag t) { this.element = t.element;
		 * this.unparsedAttrs = (String[]) t.unparsedAttrs.clone();
		 * this.startSlash = t.startSlash; this.endSlash = t.endSlash; }
		 */
		public ParsedTag(ParsedTag t, String[] outAttrs) {
			this.element = t.element;
			this.unparsedAttrs = outAttrs;
			this.startSlash = t.startSlash;
			this.endSlash = t.endSlash;
		}

		public ParsedTag(Vector v) {
			int len = v.size();
			if (len == 0) {
				element = null;
				unparsedAttrs = new String[0];
				startSlash = endSlash = false;
				return;
			}
			String s = (String) v.elementAt(len - 1);
			if (((len - 1 != 0) || (s.length() > 1)) && s.endsWith("/")) {
				s = s.substring(0, s.length() - 1);
				v.setElementAt(s, len - 1);
				if (s.length() == 0)
					len--;
				endSlash = true;
				// Don't need to set it back because everything is an I-value
			} else endSlash = false;
			s = (String) v.elementAt(0);
			if ((s.length() > 1) && s.startsWith("/")) {
				s = s.substring(1);
				v.setElementAt(s, 0);
				startSlash = true;
			} else startSlash = false;
			element = (String) v.elementAt(0);
			if (len > 1) {
				unparsedAttrs = new String[len - 1];
				for (int x = 1; x < len; x++)
					unparsedAttrs[x - 1] = (String) v.elementAt(x);
			} else
				unparsedAttrs = new String[0];
			if(logMINOR) Logger.minor(this, "Element = "+element);
		}

		public ParsedTag sanitize(HTMLParseContext pc) throws DataFilterException {
			TagVerifier tv =
				(TagVerifier) allowedTagsVerifiers.get(element.toLowerCase());
			if(logMINOR) Logger.minor(this, "Got verifier: "+tv+" for "+element);
			if (tv == null) {
				if (deleteWierdStuff) {
					return null;
				} else {
					String err = "<!-- unknown tag ";
					boolean safe = true;
					for (int x = 0; x < element.length(); x++) {
						if (!Character.isLetter(element.charAt(x))) {
							safe = false;
							break;
						}
					}
					if (safe)
						err += element + ' ';
					err += "-->";
					if (!deleteErrors)
						throwFilterException(
							"Unknown tag: " + HTMLEncoder.encode(err));
					return null;
				}
			}
			return tv.sanitize(this, pc);
		}

		public String toString() {
			if (element == null)
				return null;
			StringBuffer sb = new StringBuffer("<");
			if (startSlash)
				sb.append('/');
			sb.append(element);
			if (unparsedAttrs != null) {
				int n = unparsedAttrs.length;
				for (int i = 0; i < n; i++) {
					sb.append(' ').append(unparsedAttrs[i]);
				}
			}
			if (endSlash)
				sb.append(" /");
			sb.append('>');
			return sb.toString();
		}

		public void write(Writer w) throws IOException {
			String s = toString();
			if (s != null)
				w.write(s);
		}
	}

	static final Hashtable allowedTagsVerifiers = new Hashtable();
	static final String[] emptyStringArray = new String[0];

	static {
		allowedTagsVerifiers.put("?xml", new XmlTagVerifier());
		allowedTagsVerifiers.put(
			"!doctype",
			new DocTypeTagVerifier("!doctype"));
		allowedTagsVerifiers.put("html", new HtmlTagVerifier());
		allowedTagsVerifiers.put(
			"head",
			new TagVerifier(
				"head",
				new String[] { "id" },
				new String[] { "profile" }));
		allowedTagsVerifiers.put(
			"title",
			new TagVerifier("title", new String[] { "id" }));
		allowedTagsVerifiers.put("meta", new MetaTagVerifier());
		allowedTagsVerifiers.put(
			"body",
			new CoreTagVerifier(
				"body",
				new String[] { "bgcolor", "text", "link", "vlink", "alink" },
				new String[] { "background" },
				new String[] { "onload", "onunload" }));
		String[] group =
			{ "div", "h1", "h2", "h3", "h4", "h5", "h6", "p", "caption" };
		for (int x = 0; x < group.length; x++)
			allowedTagsVerifiers.put(
				group[x],
				new CoreTagVerifier(
					group[x],
					new String[] { "align" },
					emptyStringArray,
					emptyStringArray));
		String[] group2 =
			{
				"span",
				"address",
				"em",
				"strong",
				"dfn",
				"code",
				"samp",
				"kbd",
				"var",
				"cite",
				"abbr",
				"acronym",
				"sub",
				"sup",
				"dt",
				"dd",
				"tt",
				"i",
				"b",
				"big",
				"small",
				"strike",
				"s",
				"u",
				"noframes",
				"fieldset",
				"noscript",
				"xmp",
				"listing",
				"plaintext",
				"center",
				"bdo" };
		for (int x = 0; x < group2.length; x++)
			allowedTagsVerifiers.put(
				group2[x],
				new CoreTagVerifier(
					group2[x],
					emptyStringArray,
					emptyStringArray,
					emptyStringArray));
		allowedTagsVerifiers.put(
			"blockquote",
			new CoreTagVerifier(
				"blockquote",
				emptyStringArray,
				new String[] { "cite" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"q",
			new CoreTagVerifier(
				"q",
				emptyStringArray,
				new String[] { "cite" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"br",
			new BaseCoreTagVerifier(
				"br",
				new String[] { "clear" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"pre",
			new CoreTagVerifier(
				"pre",
				new String[] { "width", "xml:space" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"ins",
			new CoreTagVerifier(
				"ins",
				new String[] { "datetime" },
				new String[] { "cite" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"del",
			new CoreTagVerifier(
				"del",
				new String[] { "datetime" },
				new String[] { "cite" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"ul",
			new CoreTagVerifier(
				"ul",
				new String[] { "type", "compact" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"ol",
			new CoreTagVerifier(
				"ol",
				new String[] { "type", "compact", "start" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"li",
			new CoreTagVerifier(
				"li",
				new String[] { "type", "value" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"dl",
			new CoreTagVerifier(
				"dl",
				new String[] { "compact" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"dir",
			new CoreTagVerifier(
				"dir",
				new String[] { "compact" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"menu",
			new CoreTagVerifier(
				"menu",
				new String[] { "compact" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"table",
			new CoreTagVerifier(
				"table",
				new String[] {
					"summary",
					"width",
					"border",
					"frame",
					"rules",
					"cellspacing",
					"cellpadding",
					"align",
					"bgcolor" },
				new String[] { "background" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"thead",
			new CoreTagVerifier(
				"thead",
				new String[] { "align", "char", "charoff", "valign" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"tfoot",
			new CoreTagVerifier(
				"tfoot",
				new String[] { "align", "char", "charoff", "valign" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"tbody",
			new CoreTagVerifier(
				"tbody",
				new String[] { "align", "char", "charoff", "valign" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"colgroup",
			new CoreTagVerifier(
				"colgroup",
				new String[] {
					"span",
					"width",
					"align",
					"char",
					"charoff",
					"valign" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"col",
			new CoreTagVerifier(
				"col",
				new String[] {
					"span",
					"width",
					"align",
					"char",
					"charoff",
					"valign" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"tr",
			new CoreTagVerifier(
				"tr",
				new String[] {
					"align",
					"char",
					"charoff",
					"valign",
					"bgcolor" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"th",
			new CoreTagVerifier(
				"th",
				new String[] {
					"abbr",
					"axis",
					"headers",
					"scope",
					"rowspan",
					"colspan",
					"align",
					"char",
					"charoff",
					"valign",
					"nowrap",
					"bgcolor",
					"width",
					"height" },
				new String[] { "background" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"td",
			new CoreTagVerifier(
				"td",
				new String[] {
					"abbr",
					"axis",
					"headers",
					"scope",
					"rowspan",
					"colspan",
					"align",
					"char",
					"charoff",
					"valign",
					"nowrap",
					"bgcolor",
					"width",
					"height" },
				new String[] { "background" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"a",
			new LinkTagVerifier(
				"a",
				new String[] {
					"accesskey",
					"tabindex",
					"name",
					"shape",
					"coords",
					"target" },
				emptyStringArray,
				new String[] { "onfocus", "onblur" }));
		allowedTagsVerifiers.put(
			"link",
			new LinkTagVerifier(
				"link",
				new String[] { "media", "target" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"base",
			new BaseHrefTagVerifier(
				"base",
				new String[] { "id", "target" },
				new String[] { /* explicitly sanitized by class */ }));
		allowedTagsVerifiers.put(
			"img",
			new CoreTagVerifier(
				"img",
				new String[] {
					"alt",
					"name",
					"height",
					"width",
					"ismap",
					"align",
					"border",
					"hspace",
					"vspace" },
				new String[] { "src", "longdesc", "usemap" },
				emptyStringArray));
		// FIXME: object tag -
		// http://www.w3.org/TR/html4/struct/objects.html#h-13.3
		// FIXME: param tag -
		// http://www.w3.org/TR/html4/struct/objects.html#h-13.3.2
		// applet tag PROHIBITED - we do not support applets (FIXME?)
		allowedTagsVerifiers.put(
			"map",
			new CoreTagVerifier(
				"map",
				new String[] { "name" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"area",
			new CoreTagVerifier(
				"area",
				new String[] {
					"accesskey",
					"tabindex",
					"shape",
					"coords",
					"nohref",
					"alt",
					"target" },
				new String[] { "href" },
				new String[] { "onfocus", "onblur" }));
		allowedTagsVerifiers.put("style", new StyleTagVerifier());
		allowedTagsVerifiers.put(
			"font",
			new BaseCoreTagVerifier(
				"font",
				new String[] { "size", "color", "face" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"basefont",
			new BaseCoreTagVerifier(
				"basefont",
				new String[] { "size", "color", "face" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"hr",
			new CoreTagVerifier(
				"hr",
				new String[] { "align", "noshade", "size", "width" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"frameset",
			new CoreTagVerifier(
				"frameset",
				new String[] { "rows", "cols" },
				emptyStringArray,
				new String[] { "onload", "onunload" },
				false));
		allowedTagsVerifiers.put(
			"frame",
			new BaseCoreTagVerifier(
				"frame",
				new String[] {
					"name",
					"frameborder",
					"marginwidth",
					"marginheight",
					"noresize",
					"scrolling" },
				new String[] { "longdesc", "src" }));
		allowedTagsVerifiers.put(
			"iframe",
			new BaseCoreTagVerifier(
				"iframe",
				new String[] {
					"name",
					"frameborder",
					"marginwidth",
					"marginheight",
					"scrolling",
					"align",
					"height",
					"width" },
				new String[] { "longdesc", "src" }));
		
		allowedTagsVerifiers.put(
			"form",
			new FormTagVerifier(
				"form",
				new String[] {
					"name" }, // FIXME add a whitelist filter for accept
					// All other attributes are handled by FormTagVerifier.
				new String[] { },
				new String[] { "onsubmit", "onreset" }));
		allowedTagsVerifiers.put(
			"input",
			new InputTagVerifier(
				"input",
				new String[] {
					"accesskey",
					"tabindex",
					"type",
					"name",
					"value",
					"checked",
					"disabled",
					"readonly",
					"size",
					"maxlength",
					"alt",
					"ismap",
					"accept",
					"align" },
				new String[] { "src", "usemap" },
				new String[] { "onfocus", "onblur", "onselect", "onchange" }));
		allowedTagsVerifiers.put(
			"button",
			new CoreTagVerifier(
				"button",
				new String[] {
					"accesskey",
					"tabindex",
					"name",
					"value",
					"type",
					"disabled" },
				emptyStringArray,
				new String[] { "onfocus", "onblur" }));
		allowedTagsVerifiers.put(
			"select",
			new CoreTagVerifier(
				"select",
				new String[] {
					"name",
					"size",
					"multiple",
					"disabled",
					"tabindex" },
				emptyStringArray,
				new String[] { "onfocus", "onblur", "onchange" }));
		allowedTagsVerifiers.put(
			"optgroup",
			new CoreTagVerifier(
				"optgroup",
				new String[] { "disabled", "label" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"option",
			new CoreTagVerifier(
				"option",
				new String[] { "selected", "disabled", "label", "value" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put(
			"textarea",
			new CoreTagVerifier(
				"textarea",
				new String[] {
					"accesskey",
					"tabindex",
					"name",
					"rows",
					"cols",
					"disabled",
					"readonly" },
				emptyStringArray,
				new String[] { "onfocus", "onblur", "onselect", "onchange" }));
		allowedTagsVerifiers.put(
			"isindex",
			new BaseCoreTagVerifier(
				"isindex",
				new String[] { "prompt" },
				emptyStringArray));
		allowedTagsVerifiers.put(
			"label",
			new CoreTagVerifier(
				"label",
				new String[] { "for", "accesskey" },
				emptyStringArray,
				new String[] { "onfocus", "onblur" }));
		allowedTagsVerifiers.put(
			"legend",
			new CoreTagVerifier(
				"legend",
				new String[] { "accesskey", "align" },
				emptyStringArray,
				emptyStringArray));
		allowedTagsVerifiers.put("script", new ScriptTagVerifier());
	}

	static class TagVerifier {
		final String tag;
		final HashSet allowedAttrs;
		final HashSet uriAttrs;

		TagVerifier(String tag, String[] allowedAttrs) {
			this(tag, allowedAttrs, null);
		}

		TagVerifier(String tag, String[] allowedAttrs, String[] uriAttrs) {
			this.tag = tag;
			this.allowedAttrs = new HashSet();
			if (allowedAttrs != null) {
				for (int x = 0; x < allowedAttrs.length; x++)
					this.allowedAttrs.add(allowedAttrs[x]);
			}
			this.uriAttrs = new HashSet();
			if (uriAttrs != null) {
				for (int x = 0; x < uriAttrs.length; x++)
					this.uriAttrs.add(uriAttrs[x]);
			}
		}

		ParsedTag sanitize(ParsedTag t, HTMLParseContext pc) throws DataFilterException {
			Hashtable h = new Hashtable();
			boolean equals = false;
			String prevX = "";
			if (t.unparsedAttrs != null)
				for (int i = 0; i < t.unparsedAttrs.length; i++) {
					String s = t.unparsedAttrs[i];
					if (equals) {
						equals = false;
						s = stripQuotes(s);
						h.remove(prevX);
						h.put(prevX, s);
						prevX = "";
					} else {
						int idx = s.indexOf('=');
						if (idx == s.length() - 1) {
							equals = true;
							if (idx == 0) {
								// prevX already set
							} else {
								prevX = s.substring(0, s.length() - 1);
								prevX = prevX.toLowerCase();
							}
						} else if (idx > -1) {
							String x = s.substring(0, idx);
							if (x.length() == 0)
								x = prevX;
							x = x.toLowerCase();
							String y;
							if (idx == s.length() - 1)
								y = "";
							else
								y = s.substring(idx + 1, s.length());
							y = stripQuotes(y);
							h.remove(x);
							h.put(x, y);
							prevX = x;
						} else {
							h.remove(s);
							h.put(s, new Object());
							prevX = s;
						}
					}
				}
			h = sanitizeHash(h, t, pc);
			if (h == null)
				return null;
			if (t.startSlash)
				return new ParsedTag(t, null);
			String[] outAttrs = new String[h.size()];
			int i = 0;
			for (Enumeration e = h.keys(); e.hasMoreElements();) {
				String x = (String) e.nextElement();
				Object o = h.get(x);
				String y;
				if (o instanceof String)
					y = (String) o;
				else
					y = null;
				StringBuffer out = new StringBuffer(x);
				if (y != null)
					out.append( "=\"" ).append( y ).append( '"' );
				outAttrs[i++] = out.toString();
			}
			return new ParsedTag(t, outAttrs);
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = new Hashtable();
			for (Enumeration e = h.keys(); e.hasMoreElements();) {
				String x = (String) e.nextElement();
				Object o = h.get(x);
				// Straight attribs
				if (allowedAttrs.contains(x)) {
					hn.put(x, o);
					continue;
				}
				if (uriAttrs.contains(x)) {
					// URI
					if (o instanceof String) {
						// Java's URL handling doesn't seem suitable
						String uri = (String) o;
						uri = HTMLDecoder.decode(uri);
						uri = htmlSanitizeURI(uri, null, null, pc.cb, pc);
						if (uri != null) {
							uri = HTMLEncoder.encode(uri);
							hn.put(x, uri);
						}
					}
					// FIXME: rewrite absolute URLs, handle ?date= etc
				}
			}
			// lang, xml:lang and dir can go on anything
			// lang or xml:lang = language [ "-" country [ "-" variant ] ]
			// The variant can be just about anything; no way to test (avian)
			String s = getHashString(h, "lang");
			if (s != null)
				hn.put("lang", s);
			s = getHashString(h, "xml:lang");
			if (s != null)
				hn.put("xml:lang", s);
			s = getHashString(h, "dir");
			if ((s != null)
				&& (s.equalsIgnoreCase("ltr") || s.equalsIgnoreCase("rtl")))
				hn.put("dir", s);
			return hn;
		}
	}

	static String stripQuotes(String s) {
		final String quotes = "\"'";
		if (s.length() >= 2) {
			int n = quotes.length();
			for (int x = 0; x < n; x++) {
				char cc = quotes.charAt(x);
				if ((s.charAt(0) == cc) && (s.charAt(s.length() - 1) == cc)) {
					if (s.length() > 2)
						s = s.substring(1, s.length() - 1);
					else
						s = "";
					break;
				}
			}
		}
		return s;
	}

	//	static String[] titleString = new String[] {"title"};

	static abstract class ScriptStyleTagVerifier extends TagVerifier {
		ScriptStyleTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs) {
			super(tag, allowedAttrs, uriAttrs);
		}

		abstract void setStyle(boolean b, HTMLParseContext pc);

		abstract boolean getStyle(HTMLParseContext pc);

		abstract void processStyle(HTMLParseContext pc);

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			if (p.startSlash) {
				return finish(h, hn, pc);
			} else {
				return start(h, hn, pc);
			}
		}

		Hashtable finish(
			Hashtable h,
			Hashtable hn,
			HTMLParseContext pc) throws DataFilterException {
			if(logMINOR) Logger.minor(this, "Finishing script/style");
			// Finishing
			setStyle(false, pc);
			pc.styleScriptRecurseCount--;
			if (pc.styleScriptRecurseCount < 0) {
				if (deleteErrors)
					pc.writeAfterTag.append(
						"<!-- Too many nested style or script tags - ambiguous or invalid parsing -->");
				else
					throwFilterException("Too many nested </style> tags - ambiguous or invalid parsing, can't reliably filter so removing the inner tags - garbage may appear in browser");
				return null;
			}
			if(!pc.killStyle) {
				processStyle(pc);
				pc.writeStyleScriptWithTag = true;
			} else {
				pc.killStyle = false;
				pc.currentStyleScriptChunk = "";
			}
			pc.expectingBadComment = false;
			// Pass it on, no params for </style>
			return hn;
		}

		Hashtable start(Hashtable h, Hashtable hn, HTMLParseContext pc) throws DataFilterException {
			if(logMINOR) Logger.minor(this, "Starting script/style");
			pc.styleScriptRecurseCount++;
			if (pc.styleScriptRecurseCount > 1) {
				if (deleteErrors)
					pc.writeAfterTag.append(
						"<!-- Too many nested style or script tags -->");
				else
					throwFilterException("Too many nested </style> tags - ambiguous or invalid parsing, can't reliably filter so removing the inner tags - garbage may appear in browser");
				return null;
			}
			setStyle(true, pc);
			String type = getHashString(h, "type");
			if (type != null) {
				if (!type.equalsIgnoreCase("text/css") /* FIXME */
					) {
					pc.killStyle = true;
					pc.expectingBadComment = true;
					return null; // kill the tag
				}
				hn.put("type", "text/css");
			}
			return hn;
		}
	}

	static class StyleTagVerifier extends ScriptStyleTagVerifier {
		StyleTagVerifier() {
			super(
				"style",
				new String[] { "id", "media", "title", "xml:space" },
				emptyStringArray);
		}

		void setStyle(boolean b, HTMLParseContext pc) {
			pc.inStyle = b;
		}

		boolean getStyle(HTMLParseContext pc) {
			return pc.inStyle;
		}

		void processStyle(HTMLParseContext pc) {
			try {
				pc.currentStyleScriptChunk =
					sanitizeStyle(pc.currentStyleScriptChunk, pc.cb);
			} catch (DataFilterException e) {
				Logger.error(this, "Error parsing style: "+e, e);
				pc.currentStyleScriptChunk = "";
			}
		}
	}

	static class ScriptTagVerifier extends ScriptStyleTagVerifier {
		ScriptTagVerifier() {
			super(
				"script",
				new String[] {
					"id",
					"charset",
					"type",
					"language",
					"defer",
					"xml:space" },
				new String[] { "src" });
			/*
			 * FIXME: src not supported type ignored (we will need to check
			 * this when if/when we support scripts charset ignored
			 */
		}

		Hashtable sanitizeHash(
			Hashtable hn,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			// Call parent so we swallow the scripting
			super.sanitizeHash(hn, p, pc);
			return null; // Lose the tags
		}

		void setStyle(boolean b, HTMLParseContext pc) {
			pc.inScript = b;
		}

		boolean getStyle(HTMLParseContext pc) {
			return pc.inScript;
		}

		void processStyle(HTMLParseContext pc) {
			pc.currentStyleScriptChunk =
				sanitizeScripting(pc.currentStyleScriptChunk);
		}
	}

	static class BaseCoreTagVerifier extends TagVerifier {
		BaseCoreTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs) {
			super(tag, allowedAttrs, uriAttrs);
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			// %i18n dealt with by TagVerifier
			// %coreattrs
			String id = getHashString(h, "id");
			if (id != null) {
				hn.put("id", id);
				// hopefully nobody will be stupid enough to encode URLs into
				// the unique ID... :)
			}
			String classNames = getHashString(h, "class");
			if (classNames != null) {
				hn.put("class", classNames);
				// ditto
			}
			String style = getHashString(h, "style");
			if (style != null) {
				style = sanitizeStyle(style, pc.cb);
				if (style != null)
					style = escapeQuotes(style);
				if (style != null)
					hn.put("style", style);
			}
			String title = getHashString(h, "title");
			if (title != null) {
				// PARANOIA: title is PLAIN TEXT, right? In all user agents? :)
				hn.put("title", title);
			}
			return hn;
		}
	}

	static class CoreTagVerifier extends BaseCoreTagVerifier {
		final HashSet eventAttrs;
		static final String[] stdEvents =
			new String[] {
				"onclick",
				"ondblclick",
				"onmousedown",
				"onmouseup",
				"onmouseover",
				"onmousemove",
				"onmouseout",
				"onkeypress",
				"onkeydown",
				"onkeyup",
				"onload",
				"onfocus",
				"onblur",
				"oncontextmenu",
				"onresize",
				"onscroll",
				"onunload",
				"onmouseenter",
				"onchange",
				"onreset",
				"onselect",
				"onsubmit",
				"onerror",
			};

		CoreTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs,
			String[] eventAttrs) {
			this(tag, allowedAttrs, uriAttrs, eventAttrs, true);
		}

		CoreTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs,
			String[] eventAttrs,
			boolean addStdEvents) {
			super(tag, allowedAttrs, uriAttrs);
			this.eventAttrs = new HashSet();
			if (eventAttrs != null) {
				for (int x = 0; x < eventAttrs.length; x++)
					this.eventAttrs.add(eventAttrs[x]);
			}
			if (addStdEvents) {
				for (int x = 0; x < stdEvents.length; x++)
					this.eventAttrs.add(stdEvents[x]);
			}
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			// events (default and added)
			for (Iterator e = eventAttrs.iterator(); e.hasNext();) {
				String name = (String) e.next();
				String arg = getHashString(h, name);
				if (arg != null) {
					arg = sanitizeScripting(arg);
					if (arg != null)
						hn.put(name, arg);
				}
			}
			
			return hn;
		}
	}

	static class LinkTagVerifier extends CoreTagVerifier {
		LinkTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs,
			String[] eventAttrs) {
			super(tag, allowedAttrs, uriAttrs, eventAttrs);
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			String hreflang = getHashString(h, "hreflang");
			String charset = null;
			String type = getHashString(h, "type");
			if (type != null) {
				String[] typesplit = splitType(type);
				type = typesplit[0];
				if ((typesplit[1] != null) && (typesplit[1].length() > 0))
					charset = typesplit[1];
				if(logMINOR)
					Logger.debug(
							this,
							"Processing link tag, type="
							+ type
							+ ", charset="
							+ charset);
			}
			String c = getHashString(h, "charset");
			if (c != null)
				charset = c;
			String href = getHashString(h, "href");
			if (href != null) {
				final String[] rels = new String[] { "rel", "rev" };
				for (int x = 0; x < rels.length; x++) {
					String reltype = rels[x];
					String rel = getHashString(h, reltype);
					if (rel != null) {
						StringTokenizer tok = new StringTokenizer(rel, " ");
						while (tok.hasMoreTokens()) {
							String t = tok.nextToken();
							if (t.equalsIgnoreCase("alternate")
								|| t.equalsIgnoreCase("stylesheet")) {
								// FIXME: hardcoding text/css
								type = "text/css";
							} // FIXME: do we want to do anything with the
							// other possible rel's?
						}
						hn.put(reltype, rel);
					}
				}
				//				Core.logger.log(this, "Sanitizing URI: "+href+" with type "+
				//					type+" and charset "+charset,
				//					Logger.DEBUG);
				href = HTMLDecoder.decode(href);
				href = htmlSanitizeURI(href, type, charset, pc.cb, pc);
				if (href != null) {
					href = HTMLEncoder.encode(href);
					hn.put("href", href);
					if (type != null)
						hn.put("type", type);
					if (charset != null)
						hn.put("charset", charset);
					if ((charset != null) && (hreflang != null))
						hn.put("hreflang", hreflang);
				}
			}
			// FIXME: allow these if the charset and encoding are encoded into
			// the URL
			// FIXME: link types -
			// http://www.w3.org/TR/html4/types.html#type-links - the
			// stylesheet stuff, primarily - rel and rev properties - parse
			// these, use same fix as above (browser may assume text/css for
			// anything linked as a stylesheet)
			return hn;
		}
	}

	// We do not allow forms to act anywhere else than on / 
	static class FormTagVerifier extends CoreTagVerifier{
		FormTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs,
			String[] eventAttrs) {
			super(tag, allowedAttrs, uriAttrs, eventAttrs);
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			if(p.startSlash) {
				// Allow, but only with standard elements
				return hn;
			}
			String method = (String) h.get("method");
			String action = (String) h.get("action");
			String finalAction;
			try {
				finalAction = pc.cb.processForm(method, action);
			} catch (CommentException e) {
	            pc.writeAfterTag.append("<!-- ").append(HTMLEncoder.encode(e.toString())).append(" -->");
				return null;
			}
			if(finalAction == null) return null;
			hn.put("method", method);
			hn.put("action", finalAction);
			// Force enctype and accept-charset to acceptable values.
			hn.put("enctype", "multipart/form-data");
			hn.put("accept-charset", "UTF-8");
			return hn;
		}
	}
	
	static class InputTagVerifier extends CoreTagVerifier{
		final HashSet allowedTypes;
		String[] types = new String[]{
			"text",
			"password",
			"checkbox",
			"radio",
			"submit",
			"reset,",
			// no ! file
			"hidden",
			"image",
			"button"
		};
		
		InputTagVerifier(
			String tag,
			String[] allowedAttrs,
			String[] uriAttrs,
			String[] eventAttrs) {
			super(tag, allowedAttrs, uriAttrs, eventAttrs);
			this.allowedTypes = new HashSet();
			if (types != null) {
				for (int x = 0; x < types.length; x++)
					this.allowedTypes.add(types[x]);
			}
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			
			// We drop the whole <input> if type isn't allowed
			if(!allowedTypes.contains(hn.get("type"))){
				return null;
			}
			
			return hn;
		}
	}
	
	static class MetaTagVerifier extends TagVerifier {
		MetaTagVerifier() {
			super("meta", new String[] { "id" });
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			/*
			 * Several possibilities: a) meta http-equiv=X content=Y b) meta
			 * name=X content=Y
			 */
			String http_equiv = getHashString(h, "http-equiv");
			String name = getHashString(h, "name");
			String content = getHashString(h, "content");
			String scheme = getHashString(h, "scheme");
			if(logMINOR) Logger.minor(this, "meta: name="+name+", content="+content+", http-equiv="+http_equiv+", scheme="+scheme);
			if (content != null) {
				if ((name != null) && (http_equiv == null)) {
					if (name.equalsIgnoreCase("Author")) {
						hn.put("name", name);
						hn.put("content", content);
					} else if (name.equalsIgnoreCase("Keywords")) {
						hn.put("name", name);
						hn.put("content", content);
					} else if (name.equalsIgnoreCase("Description")) {
						hn.put("name", name);
						hn.put("content", content);
					}
				} else if ((http_equiv != null) && (name == null)) {
					if (http_equiv.equalsIgnoreCase("Expires")) {
						hn.put("http-equiv", http_equiv);
						hn.put("content", content);
					} else if (
						http_equiv.equalsIgnoreCase("Content-Script-Type")) {
						// We don't support script at this time.
					} else if (
						http_equiv.equalsIgnoreCase("Content-Style-Type")) {
						// FIXME: charsets
						if (content.equalsIgnoreCase("text/css")) {
							// FIXME: selectable style languages - only matters
							// when we have implemented more than one
							// FIXME: if we ever do allow it... the spec
							// http://www.w3.org/TR/html4/present/styles.html#h-14.2.1
							// says only the last definition counts...
							//        but it only counts if it's in the HEAD section,
							// so we DONT need to parse the whole doc
							hn.put("http-equiv", http_equiv);
							hn.put("content", content);
						}
						// FIXME: add some more headers - Dublin Core?
					} else if (http_equiv.equalsIgnoreCase("Content-Type")) {
						if(logMINOR) Logger.minor(this, "Found http-equiv content-type="+content);
						String[] typesplit = splitType(content);
						if(logMINOR) {
							for(int i=0;i<typesplit.length;i++)
								Logger.minor(this, "["+i+"] = "+typesplit[i]);
						}
						if (typesplit[0].equalsIgnoreCase("text/html")
							&& ((typesplit[1] == null)
								|| typesplit[1].equalsIgnoreCase(pc.charset))) {
							hn.put("http-equiv", http_equiv);
							hn.put(
								"content",
								typesplit[0]
									+ (typesplit[1] != null
										? "; charset=" + typesplit[1]
										: ""));
						}
						if(typesplit[1] != null)
							pc.detectedCharset = typesplit[1].trim();
					} else if (
						http_equiv.equalsIgnoreCase("Content-Language")) {
						hn.put("http-equiv", "Content-Language");
						hn.put("content", content);
					}
				}
			}
			if (hn.isEmpty())
				return null;
			return hn;
		}
	}

	static class DocTypeTagVerifier extends TagVerifier {
		DocTypeTagVerifier(String tag) {
			super(tag, null);
		}

		static final Hashtable DTDs = new Hashtable();

		static {
			DTDs.put(
				"-//W3C//DTD XHTML 1.0 Strict//EN",
				"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
			DTDs.put(
				"-//W3C//DTD XHTML 1.0 Transitional//EN",
				"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd");
			DTDs.put(
				"-//W3C//DTD XHTML 1.0 Frameset//EN",
				"http://www.w3.org/TR/xhtml1/DTD/xhtml1-frameset.dtd");
			DTDs.put(
				"-//W3C//DTD HTML 4.01//EN",
				"http://www.w3.org/TR/html4/strict.dtd");
			DTDs.put(
				"-//W3C//DTD HTML 4.01 Transitional//EN",
				"http://www.w3.org/TR/html4/loose.dtd");
			DTDs.put(
				"-//W3C//DTD HTML 4.01 Frameset//EN",
				"http://www.w3.org/TR/html4/frameset.dtd");
			DTDs.put("-//W3C//DTD HTML 3.2 Final//EN", new Object());
		}

		ParsedTag sanitize(ParsedTag t, HTMLParseContext pc) {
			if (!((t.unparsedAttrs.length == 3) || (t.unparsedAttrs.length == 4)))
				return null;
			if (!t.unparsedAttrs[0].equalsIgnoreCase("html"))
				return null;
			if (!t.unparsedAttrs[1].equalsIgnoreCase("public"))
				return null;
			String s = stripQuotes(t.unparsedAttrs[2]);
			if (!DTDs.containsKey(s))
				return null;
			if (t.unparsedAttrs.length == 4) {
				String ss = stripQuotes(t.unparsedAttrs[3]);
				String spec = getHashString(DTDs, s);
				if ((spec != null) && !spec.equals(ss))
					return null;
			}
			return t;
		}
	}

	static class XmlTagVerifier extends TagVerifier {
		XmlTagVerifier() {
			super("?xml", null);
		}

		ParsedTag sanitize(ParsedTag t, HTMLParseContext pc) {
			if (t.unparsedAttrs.length != 2)
				return null;
			if (!t.unparsedAttrs[0].equals("version=\"1.0\""))
				return null;
			if (!t.unparsedAttrs[1].startsWith("encoding=\"")
				&& !t.unparsedAttrs[1].endsWith("\"?"))
				return null;
			if (!t
				.unparsedAttrs[1]
				.substring(10, t.unparsedAttrs[1].length() - 2)
				.equalsIgnoreCase(pc.charset))
				return null;
			return t;
		}
	}

	static class HtmlTagVerifier extends TagVerifier {
		HtmlTagVerifier() {
			super("html", new String[] { "id", "version" });
		}

		Hashtable sanitizeHash(
			Hashtable h,
			ParsedTag p,
			HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			String xmlns = getHashString(h, "xmlns");
			if ((xmlns != null) && xmlns.equals("http://www.w3.org/1999/xhtml"))
				hn.put("xmlns", xmlns);
			return hn;
		}
	}

	static class BaseHrefTagVerifier extends TagVerifier {

		BaseHrefTagVerifier(String string, String[] strings, String[] strings2) {
			super(string, strings, strings2);
		}
		
		Hashtable sanitizeHash(
				Hashtable h,
				ParsedTag p,
				HTMLParseContext pc) throws DataFilterException {
			Hashtable hn = super.sanitizeHash(h, p, pc);
			// Get the already-sanitized version.
			String baseHref = getHashString(hn, "href");
			if(baseHref != null) {
				String ref = pc.cb.onBaseHref(baseHref);
				if(ref != null)
					hn.put("href", ref);
			}
			return hn;
		}

	}
	
	static String sanitizeStyle(String style, FilterCallback cb) throws DataFilterException {
		if(style == null) return null;
		Reader r = new StringReader(style);
		Writer w = new StringWriter();
		style = style.trim();
		if(logMINOR) Logger.minor(HTMLFilter.class, "Sanitizing style: " + style);
		CSSParser pc = new CSSParser(r, w, false, cb);
		try {
			pc.parse();
		} catch (IOException e) {
			Logger.error(
				HTMLFilter.class,
				"IOException parsing inline CSS!");
		} catch (Error e) {
			if (e.getMessage().equals("Error: could not match input")) {
				// this sucks, it should be a proper exception
				Logger.normal(
					HTMLFilter.class,
					"CSS Parse Error!",
					e);
				return "/* Could not match input style */";
			} else
				throw e;
		}
		String s = w.toString();
		if ((s == null) || (s.length() == 0))
			return null;
		//		Core.logger.log(SaferFilter.class, "Style now: " + s, Logger.DEBUG);
		if(logMINOR) Logger.debug(HTMLFilter.class, "Style finally: " + s);
		return s;
	}

	static String escapeQuotes(String s) {
		StringBuffer buf = new StringBuffer(s.length());
		for (int x = 0; x < s.length(); x++) {
			char c = s.charAt(x);
			if (c == '\"') {
				buf.append("&quot;");
			} else {
				buf.append(c);
			}
		}
		return buf.toString();
	}

	static String sanitizeScripting(String script) {
		// Kill it. At some point we may want to allow certain recipes - FIXME
		return null;
	}

	static String sanitizeURI(String uri, FilterCallback cb) throws CommentException {
		return sanitizeURI(uri, null, null, cb);
	}

	/*
	 * While we're only interested in the type and the charset, the format is a
	 * lot more flexible than that. (avian) TEXT/PLAIN; format=flowed;
	 * charset=US-ASCII IMAGE/JPEG; name=test.jpeg; x-unix-mode=0644
	 */
	static String[] splitType(String type) {
		StringFieldParser sfp;
		String charset = null, param, name, value;
		int x;

		sfp = new StringFieldParser(type, ';');
		type = sfp.nextField().trim();
		while (sfp.hasMoreFields()) {
			param = sfp.nextField();
			x = param.indexOf('=');
			if (x != -1) {
				name = param.substring(0, x).trim();
				value = param.substring(x + 1).trim();
				if (name.equals("charset"))
					charset = value;
			}
		}
		return new String[] { type, charset };
	}

	// A simple string splitter
	// StringTokenizer doesn't work well for our purpose. (avian)
	static class StringFieldParser {
		private String str;
		private int maxPos, curPos;
		private char c;

		public StringFieldParser(String str) {
			this(str, '\t');
		}

		public StringFieldParser(String str, char c) {
			this.str = str;
			this.maxPos = str.length();
			this.curPos = 0;
			this.c = c;
		}

		public boolean hasMoreFields() {
			return curPos <= maxPos;
		}

		public String nextField() {
			int start, end;

			if (curPos > maxPos)
				return null;
			start = curPos;
			while ((curPos < maxPos) && (str.charAt(curPos) != c))
				curPos++;
			end = curPos;
			curPos++;
			return str.substring(start, end);
		}
	}

	static String htmlSanitizeURI(
			String suri,
			String overrideType,
			String overrideCharset,
			FilterCallback cb,
			HTMLParseContext pc) {
		try {
			return sanitizeURI(suri, overrideType, overrideCharset, cb);
		} catch (CommentException e) {
            pc.writeAfterTag.append("<!-- ").append(HTMLEncoder.encode(e.toString())).append(" -->");
			return null;
		}
	}
	
	static String sanitizeURI(
		String suri,
		String overrideType,
		String overrideCharset,
		FilterCallback cb) throws CommentException {
		if(logMINOR)
			Logger.minor(HTMLFilter.class, "Sanitizing URI: "+suri+" ( override type "+overrideType +" override charset "+overrideCharset+" )");
		if((overrideCharset != null) && (overrideCharset.length() > 0))
			overrideType += "; charset="+overrideCharset;
		return cb.processURI(suri, overrideType);
	}

	static String getHashString(Hashtable h, String key) {
		Object o = h.get(key);
		if (o == null)
			return null;
		if (o instanceof String)
			return (String) o;
		else
			return null;
	}

}
