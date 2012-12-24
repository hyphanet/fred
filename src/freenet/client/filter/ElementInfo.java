package freenet.client.filter;
import java.util.HashSet;
public class ElementInfo {

	static boolean disallowUnknownSpecificFonts = false;
	/** If true, and above is false, allow font names only if they consist
	 * entirely of spaces, numbers, letters, and ._-,+~
	 */
	static boolean disallowNonAlnumFonts = true;
	
	private final static HashSet<String> VOID_ELEMENTS=new HashSet<String>();
	static {
		VOID_ELEMENTS.add("area");
		VOID_ELEMENTS.add("base");
		VOID_ELEMENTS.add("basefont");
		VOID_ELEMENTS.add("bgsound");
		VOID_ELEMENTS.add("br");
		VOID_ELEMENTS.add("col");
		VOID_ELEMENTS.add("command");
		VOID_ELEMENTS.add("embed");
		VOID_ELEMENTS.add("event-source");
		VOID_ELEMENTS.add("frame");
		VOID_ELEMENTS.add("hr");
		VOID_ELEMENTS.add("img");
		VOID_ELEMENTS.add("input");
		VOID_ELEMENTS.add("keygen");
		VOID_ELEMENTS.add("link");
		VOID_ELEMENTS.add("meta");
		VOID_ELEMENTS.add("param");
		VOID_ELEMENTS.add("source");
		VOID_ELEMENTS.add("spacer");
		VOID_ELEMENTS.add("wbr");
	}
	//Taken from HTMLFilter
	public final static HashSet<String> HTML_ELEMENTS=new HashSet<String>();
	static {
		HTML_ELEMENTS.add("html");
		HTML_ELEMENTS.add("head");
		HTML_ELEMENTS.add("title");
		HTML_ELEMENTS.add("body");
		HTML_ELEMENTS.add("div");
		HTML_ELEMENTS.add("header");
		HTML_ELEMENTS.add("nav");
		HTML_ELEMENTS.add("address");
		HTML_ELEMENTS.add("hgroup");
		HTML_ELEMENTS.add("aside");
		HTML_ELEMENTS.add("section");
		HTML_ELEMENTS.add("article");
		HTML_ELEMENTS.add("footer");
		HTML_ELEMENTS.add("h1");
		HTML_ELEMENTS.add("h2");
		HTML_ELEMENTS.add("h3");
		HTML_ELEMENTS.add("h4");
		HTML_ELEMENTS.add("h5");
		HTML_ELEMENTS.add("h6");
		HTML_ELEMENTS.add("p");
		HTML_ELEMENTS.add("caption");
		HTML_ELEMENTS.add("span");
		HTML_ELEMENTS.add("address");
		HTML_ELEMENTS.add("em");
		HTML_ELEMENTS.add("strong");
		HTML_ELEMENTS.add("dfn");
		HTML_ELEMENTS.add("code");
		HTML_ELEMENTS.add("samp");
		HTML_ELEMENTS.add("kbd");
		HTML_ELEMENTS.add("var");
		HTML_ELEMENTS.add("cite");
		HTML_ELEMENTS.add("abbr");
		HTML_ELEMENTS.add("acronym");
		HTML_ELEMENTS.add("sub");
		HTML_ELEMENTS.add("sup");
		HTML_ELEMENTS.add("dt");
		HTML_ELEMENTS.add("dd");
		HTML_ELEMENTS.add("tt");
		HTML_ELEMENTS.add("i");
		HTML_ELEMENTS.add("b");
		HTML_ELEMENTS.add("big");
		HTML_ELEMENTS.add("small");
		HTML_ELEMENTS.add("strike");
		HTML_ELEMENTS.add("s");
		HTML_ELEMENTS.add("u");
		HTML_ELEMENTS.add("noframes");
		HTML_ELEMENTS.add("fieldset");
		HTML_ELEMENTS.add("xmp");
		HTML_ELEMENTS.add("listing");
		HTML_ELEMENTS.add("plaintext");
		HTML_ELEMENTS.add("center");
		HTML_ELEMENTS.add("bdo");
		HTML_ELEMENTS.add("blockquote");
		HTML_ELEMENTS.add("q");
		HTML_ELEMENTS.add("br");
		HTML_ELEMENTS.add("pre");
		HTML_ELEMENTS.add("ins");
		HTML_ELEMENTS.add("del");
		HTML_ELEMENTS.add("ul");
		HTML_ELEMENTS.add("ol");
		HTML_ELEMENTS.add("li");
		HTML_ELEMENTS.add("dl");
		HTML_ELEMENTS.add("dir");
		HTML_ELEMENTS.add("menu");
		HTML_ELEMENTS.add("table");
		HTML_ELEMENTS.add("thead");
		HTML_ELEMENTS.add("tfoot");
		HTML_ELEMENTS.add("tbody");
		HTML_ELEMENTS.add("colgroup");
		HTML_ELEMENTS.add("col");
		HTML_ELEMENTS.add("tr");
		HTML_ELEMENTS.add("th");
		HTML_ELEMENTS.add("td");
		HTML_ELEMENTS.add("a");
		HTML_ELEMENTS.add("img");
		HTML_ELEMENTS.add("map");
		HTML_ELEMENTS.add("area");
		HTML_ELEMENTS.add("font");
		HTML_ELEMENTS.add("basefont");
		HTML_ELEMENTS.add("hr");
		HTML_ELEMENTS.add("frame");
		HTML_ELEMENTS.add("frameset");
		HTML_ELEMENTS.add("form");
		HTML_ELEMENTS.add("input");
		HTML_ELEMENTS.add("button");
		HTML_ELEMENTS.add("select");
		HTML_ELEMENTS.add("optgroup");
		HTML_ELEMENTS.add("option");
		HTML_ELEMENTS.add("textarea");
		HTML_ELEMENTS.add("isindex");
		HTML_ELEMENTS.add("label");
		HTML_ELEMENTS.add("legend");

	}
	
	static final HashSet<String> REPLACED_ELEMENTS = new HashSet<String>();
	static {
		REPLACED_ELEMENTS.add("img");
		REPLACED_ELEMENTS.add("object");
		REPLACED_ELEMENTS.add("textarea");
		REPLACED_ELEMENTS.add("select");
		REPLACED_ELEMENTS.add("input"); // ??? Most input's aren't???
		REPLACED_ELEMENTS.add("applet");
		REPLACED_ELEMENTS.add("button");
	}
	
	//public final static String[] HTMLELEMENTSARRAY=HTML_ELEMENTS.toArray(new String[0]);
	//public final static String[] TABLEELEMENTS=new String[]{"table","colgroup","col","tbody","thead","tfoot","tr","td","caption"};
	//public final static String[] ALLBUTNONREPLACEDINLINEELEMENTS= makeAllButNonReplacedInlineElements();
	//public final static String[] BLOCKLEVELELEMENTS=new String[]{"address","blockquote","center","dir","div","dl","fieldset","form","h1","h2","h3","h4","h5","h6","hr","isindex","menu","noframes","noscript","ol","p","pre","table","ul","dd","dt","frameset","li","tbody","td","tfoot","th","thead","tr"};
	//public final static String[] INLINEELEMENTS=new String[]{"a","abbr","acronym","b", "basefont","bdo","big","br","cite","code","dfn","em","font","i","img","input","kbd","label","q","s","samp","select","small","span","strike","strong","sub","sup","textarea","tt","u","var"};
	 
	//public final static String[] NONREPLACEDINLINEELEMENTS=new String[]{"a","abbr","acronym","b", "basefont","bdo","big","br","cite","code","dfn","em","font","i","input","kbd","label","q","s","samp","select","small","span","strike","strong","sub","sup","textarea","tt","u","var" };
	//public final static String[] ELEMENTSFORPADDING;
//	static
//	{
//		List<String> list = new ArrayList<String>(Arrays.asList(HTMLELEMENTSARRAY));
//		list.removeAll(Arrays.asList(new String[]{"table","th","tr","td","table","thead","tfoot","tbody","colgroup","col"}));
//		ELEMENTSFORPADDING = list.toArray(new String[0]);
//	}
	
	// FIXME add some more languages.
	public final static HashSet<String> LANGUAGES=new HashSet<String>();
	static
	{
		LANGUAGES.add("az");
		LANGUAGES.add("be"); 
		LANGUAGES.add("bg");
		LANGUAGES.add("cs");
		LANGUAGES.add("de");
		LANGUAGES.add("el");
		LANGUAGES.add("en");
		LANGUAGES.add("es");
		LANGUAGES.add("fi");
		LANGUAGES.add("fr");
		LANGUAGES.add("id");
		LANGUAGES.add("it");
		LANGUAGES.add("ja");
		LANGUAGES.add("ka");
		LANGUAGES.add("kk");
		LANGUAGES.add("ky");
		LANGUAGES.add("lv");
		LANGUAGES.add("mo");
		LANGUAGES.add("nl");
		LANGUAGES.add("no");
		LANGUAGES.add("pl");
		LANGUAGES.add("pt");
		LANGUAGES.add("ro");
		LANGUAGES.add("ru");
		LANGUAGES.add("sv");
		LANGUAGES.add("tl");
		LANGUAGES.add("tr");
		LANGUAGES.add("tt");
		LANGUAGES.add("uk");
		LANGUAGES.add("zh-hans");
		LANGUAGES.add("zh-hant");
	}
	
	public final static String[] MEDIAARRAY= new String[]{"all","aural","braille","embossed","handheld","print","projection","screen","speech","tty","tv"};
	public final static String[] VISUALMEDIA= new String[]{"handheld","print","projection","screen","tty","tv"};
	public final static String[] AURALMEDIA=new String[]{"speech","aural"};
	public final static String[] VISUALPAGEDMEDIA=new String[]{"embossed","handheld","print","projection","screen","tty","tv"};
	public final static String[] VISUALINTERACTIVEMEDIA=new String[]{"braille","handheld","print","projection","screen","speech","tty","tv"};	
	
	
	public final static int UPPERLIMIT=10;
	
	public final static String[] FONT_LIST=new String[]{"arial", "helvetica","arial black","gadget", "comic sans ms", "comic sans ms5","courier new", "courier6", "monospace georgia1", "georgia","impact", "impact5", "charcoal6","lucida console", "monaco5","lucida sans unicode", "lucida grande","palatino linotype", "book antiqua3", "palatino6","tahoma", "geneva","times new roman", "times","trebuchet ms1", "helvetica","verdana", "webdings", "webdings2", "wingdings", "zapf dingbats", "wingdings2", "zapf dingbats2","ms sans serif4", "ms serif4", "new york6"};
	public final static String[] GENERIC_FONT_KEYWORDS = new String[] { "serif","sans-serif","cursive","fantasy","monospace" };
	public final static String[] GENERIC_VOICE_KEYWORDS = new String[] { "male", "female", "child" };
	public final static HashSet<String> PSEUDOCLASS=new HashSet<String>();
	static {
		PSEUDOCLASS.add("first-child");
		PSEUDOCLASS.add("last-child");
		PSEUDOCLASS.add("link");
		PSEUDOCLASS.add("visited");
		PSEUDOCLASS.add("hover");
		PSEUDOCLASS.add("active");
		PSEUDOCLASS.add("focus");
		PSEUDOCLASS.add("lang");
		PSEUDOCLASS.add("first-line");
		PSEUDOCLASS.add("first-letter");
		PSEUDOCLASS.add("before");
		PSEUDOCLASS.add("after");
		PSEUDOCLASS.add("target");
	}

	// FIXME use HashSet<String> or even enum.
	
	public static boolean isSpecificFontFamily(String font) {
		if(disallowUnknownSpecificFonts) {
			for(String s : FONT_LIST)
				if(s.equals(font)) return true;
			return false;
		} else if(disallowNonAlnumFonts) {
			for(int i=0;i<font.length();i++) {
				char c = font.charAt(i);
				if(!(Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == '_' || c == '-' || c == ',' || c == '+' || c == '~')) return false;
			}
			return true;
		}
		// Allow anything. The caller will have enforced that unquoted font names must not contain non-identifier characters.
		return true;
	}
	
	public static boolean isSpecificVoiceFamily(String font) {
//		if(disallowUnknownSpecificFonts) {
//			for(String s : FONT_LIST)
//				if(s.equals(font)) return true;
//			return false;
		//} else 
		if(disallowNonAlnumFonts) {
			for(int i=0;i<font.length();i++) {
				char c = font.charAt(i);
				if(!(Character.isLetterOrDigit(c) || c == ' ' || c == '.' || c == '_' || c == '-' || c == ',' || c == '+' || c == '~')) return false;
			}
			return true;
		}
		// Allow anything. The caller will have enforced that unquoted font names must not contain non-identifier characters.
		return true;
	}
	
	private static String[] makeAllButNonReplacedInlineElements() {
		// all elements but non-replaced inline elements, table rows, and row groups
		HashSet<String> elements = new HashSet<String>(HTML_ELEMENTS);
		for(String s : REPLACED_ELEMENTS)
			elements.remove(s);
		elements.remove("tr");
		elements.remove("tbody");
		return elements.toArray(new String[elements.size()]);
	}

	/** font must be lower-case */
	public static boolean isGenericFontFamily(String font) {
		for(String s : GENERIC_FONT_KEYWORDS)
			if(s.equals(font)) return true;
		return false;
	}
	
	/** font must be lower-case */
	public static boolean isGenericVoiceFamily(String font) {
		for(String s : GENERIC_VOICE_KEYWORDS)
			if(s.equals(font)) return true;
		return false;
	}
	
	public static boolean isWordPrefixOrMatchOfSpecificFontFamily(String prefix) {
		String extraSpace = prefix + " ";
		for(String s : FONT_LIST)
			if(s.equals(prefix) || s.startsWith(extraSpace)) return true;
		return false;
	}
	
	public static boolean isVoidElement(String element) {
		
		return VOID_ELEMENTS.contains(element);
	}
	
	/** These elements are frequently used one after the other, and are invalid inside each other.
	 * AFAICS only <li>. */
	public static boolean tryAutoClose(String element) {
		if("li".equals(element)) return true;
		return false;
	}
	
	public static boolean isValidHTMLTag(String tag)
	{
		return (HTML_ELEMENTS.contains(tag.toLowerCase())||VOID_ELEMENTS.contains(tag.toLowerCase()));
	}
	
	/**
	 * According to the HTML spec, ID must begin with a letter A-Za-z, and 
	 * may be followed by any number of [a-zA-Z0-9-_:.]. It is not spelled
	 * out what is allowed in class, but we are assuming the same is true
	 * there.
	 * @return Whether the string is a valid ID for HTML purposes.
	 */
	public static boolean isValidName(String name)
	{
		if(name.length()==0)
		{
			return false;
		}
		else
		{
			if(!((name.charAt(0)>='a' && name.charAt(0)<='z') || (name.charAt(0)>='A' && name.charAt(0)<='Z')))
			{
				return false;
			}
			else
			{
				
				for(int i=1;i<name.length();i++)
				{
					if(!((name.charAt(i)>='a' && name.charAt(i)<='z') || (name.charAt(i)>='A' && name.charAt(i)<='Z') || (name.charAt(i)>='0' && name.charAt(i)<='9') || name.charAt(i)=='_' || name.charAt(i)==':'  || name.charAt(i)=='.' || name.charAt(i)=='-'))
					{
						return false;
					}
				}
				
			}
			return true;
		}
	}
	
	public static boolean isValidIdentifier(String name)
	{
		if(name.length()==0)
		{
			return false;
		}
		else
		{
			boolean escape = false;
			boolean escapeNewline = false;
			boolean digitsAllowed = false;
			int unicodeChars = 0;
			for(int i=0;i<name.length();i++) {
				char c = name.charAt(i);
				if(escape) {
					// Whitespace after an escape can be \r\n
					if(escapeNewline) {
						escapeNewline = false;
						escape = false;
						if(c == '\n') continue;;
					}
					escapeNewline = false;
					if(('0' <= c && '9' >= c) || ('a' <= c && 'f' >= c) || ('A' <= c && 'F' >= c)) {
						if(unicodeChars == 5) {
							// Full 6 character escape.
							unicodeChars = 0;
							escape = false;
							continue;
						} else {
							unicodeChars++;
							continue;
						}
					}
					if(unicodeChars > 0) {
						if(c == '\r') {
							escapeNewline = true;
							unicodeChars = 0;
							continue;
						} else if(!(c == '\n' || c == '\f' || c == '\t' || c == ' ')) {
							// Only whitespace is allowed after a unicode character escape.
							return false;
						}
					}
					if(c == '\r' || c == '\n' || c == '\f')
						// Explicitly not allowed to escape these, see grammar, and 4.1.3.
						return false;
					// Directly escaped character
					escape = false;
					continue;
				}
				if(digitsAllowed && c>='0' && c<='9') {
					continue;
				}
				if(c == '-') continue;
				digitsAllowed = true;
				if(c == '_') continue;
				if(c == '\\') {
					escape = true;
					continue;
				}
				if(c>='a' && c<='z') continue;
				if(c>='A' && c<='Z') continue;
				// Spec strictly speaking allows control chars, but let's disallow them here as a paranoid precaution.
				if(c >= 0xA1 && !Character.isISOControl(c)) continue;
				return false;
			}
			
			if(escape) {
				// Still in an escape.
				// Might be dangerous e.g. escaping the ] in E[foo=blah] could change the meaning completely.
				return false;
			}
			
			return true;
		}
	}
		
		public static boolean isValidPseudoClass(String cname)
		{
			if(cname.indexOf(':') != -1) {
				// Pseudo-classes can be chained, at least dynamic ones can, see CSS2.1 section 5.11.3
				String[] split = cname.split(":");
				for(String s : split)
					if(!isValidPseudoClass(s)) return false;
				return true;
			}
			cname=cname.toLowerCase();
			if(PSEUDOCLASS.contains(cname))
				return true;

			
			else if(cname.indexOf("lang")!=-1)
			{
				int langIndex=cname.indexOf("lang");
				int firstIndex=cname.indexOf("(");
				int secondIndex=cname.lastIndexOf(")");
				if(cname.substring(langIndex+4,firstIndex).trim().equals("") && cname.substring(0,langIndex).trim().equals("") && cname.substring(secondIndex+1,cname.length()).trim().equals(""))
				{
					String language=CSSTokenizerFilter.removeOuterQuotes(cname.substring(firstIndex+1,secondIndex).trim());
					
					// FIXME accept unknown languages as long as they are [a-z-]
					if(LANGUAGES.contains(language))
						return true;
				}
				
			}
			
			return false;
		}

		/** Is the string valid and safe?
		 * @param name The string to parse, in its original encoded form.
		 */
		public static boolean isValidString(String name)
		{
			boolean escape = false;
			boolean escapeNewline = false;
			int unicodeChars = 0;
			for(int i=0;i<name.length();i++) {
			char c = name.charAt(i);
				if(escape) {
					// Whitespace after an escape can be \r\n
					if(escapeNewline) {
						escapeNewline = false;
						escape = false;
						if(c == '\n') continue;;
					}
					escapeNewline = false;
					if(('0' <= c && '9' >= c) || ('a' <= c && 'f' >= c) || ('A' <= c && 'F' >= c)) {
						if(unicodeChars == 5) {
							// Full 6 character escape.
							unicodeChars = 0;
							escape = false;
							continue;
						} else {
							unicodeChars++;
							continue;
						}
					}
					if(unicodeChars > 0) {
						if(c == '\r') {
							escapeNewline = true;
							unicodeChars = 0;
							continue;
						} else if(!(c == '\n' || c == '\f' || c == '\t' || c == ' ')) {
							// Only whitespace is allowed after a unicode character escape.
							return false;
						}
					}
					if(c == '\r') {
						escapeNewline = true;
						continue;
					}
					if(c == '\r' || c == '\n' || c == '\f') {
						// Newline is allowed escaped in a string. 
						escape = false;
						continue;
					}
					
					// Directly escaped character
					escape = false;
					continue;
				}
				
				// No unquoted quotes
				if(c == '\'' || c == '\"') return false;
				
				// No unquoted newlines
				if(c == '\r' || c == '\n' || c == '\f') return false;
				
				if(c == '\\') {
					escape = true;
					continue;
				}
				
				// Allow everything else.
				continue;
			}
			
			if(escape) {
				// Still in an escape.
				// Might be dangerous.
				return false;
			}
				
			return true;
		}
		
		public static final boolean ALLOW_ALL_VALID_STRINGS = true;
		
		public static boolean isValidStringDecoded(String s) {
			// REDFLAG: All strings which are parsed and decoded correctly (which has happened before this point) are valid.
			// That is pretty much what the spec says, and there would seem to be no risk, except for with wierd extensions, which can act on plain text so there isn't much we can do.
			return true;
		}

		public static boolean isValidStringWithQuotes(String string) {
			if(string.length() < 2) return false;
			if((string.charAt(0) == '\'' && string.charAt(string.length()-1) == '\'') ||
					(string.charAt(0) == '\"' && string.charAt(string.length()-1) == '\"')) {
				string = string.substring(1, string.length()-1);
				return isValidString(string);
			} else return false;
		}
		
			
						

}
