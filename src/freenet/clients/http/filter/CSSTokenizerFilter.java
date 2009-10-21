/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */ 

package freenet.clients.http.filter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import freenet.support.Logger;

/** Comprehensive CSS2.1 filter. The old jflex-based filter was very far 
 * from comprehensive.
 * @author kurmiashish
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 * 
 * FIXME: Rewrite to parse properly. This works but is rather spaghettified.
 * JFlex on its own obviously won't work, but JFlex plus a proper grammr 
 * should work fine.
 * 
 * According to the CSS2.1 spec, in some cases spaces between tokens are
 * optional. We do NOT support this! A grammar-based parser might, although
 * really it's horrible, we shouldn't support it if it's not widely used...
 * See end of 1.4.2.1.
 */
class CSSTokenizerFilter {
	private Reader r;
	Writer w = null;
	FilterCallback cb;
	// FIXME use this
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;
	private final String passedCharset;
	private String detectedCharset;
	private final boolean stopAtDetectedCharset;
	private final boolean isInline;
	
	static {
		Logger.registerClass(CSSTokenizerFilter.class);
	}
	
	CSSTokenizerFilter(){
		passedCharset = "UTF-8";
		stopAtDetectedCharset = false;
		isInline = false;
	}
	
//	private static PrintStream log;

	public static void log(String s)
	{
		Logger.debug(CSSTokenizerFilter.class,s);
		//System.out.println("CSSTokenizerFilter: "+s);
		//log.println(s);
	}
	CSSTokenizerFilter(Reader r, Writer w, FilterCallback cb, String charset, boolean stopAtDetectedCharset, boolean isInline) {
		this.r=r;
		this.w = w;
		this.cb=cb;
		passedCharset = charset;
		this.stopAtDetectedCharset = stopAtDetectedCharset;
		this.isInline = isInline;
//		try {
//			log = new PrintStream(new FileOutputStream("log"));
//		} catch (FileNotFoundException e) {
//			throw new Error(e);
//		}
	}

	public boolean isValidURI(String URI)
	{
		try
		{
			return URI.equals(cb.processURI(URI, null));
		}
		catch(CommentException e)
		{
			return false;
		}
	}


	//Function to merge two arrays into third array.
	public static <T> T[] concat(T[] a, T[] b) {
		final int alen = a.length;
		final int blen = b.length;
		if (alen == 0) {
			return b;
		}
		if (blen == 0) {
			return a;
		}
		final T[] result = (T[]) java.lang.reflect.Array.
		newInstance(a.getClass().getComponentType(), alen + blen);
		System.arraycopy(a, 0, result, 0, alen);
		System.arraycopy(b, 0, result, alen, blen);
		return result;
	}


	/* To save the memory, only those Verifier objects would be created which are actually present in the CSS document.
	 * allelementVerifiers contains all the CSS property tags as String. All loaded Verifier objects are stored in elementVerifier.
	 * When retrieving a Verifier object, first it is searched in elementVerifiers to see if it is already loaded.
	 * If it is not loaded then allelementVerifiers is checked to see if the property name is valid. If it is valid, then the desired Verifier object is loaded in allelemntVerifiers.
	 */
	
	// FIXME this is probably overkill, initialising all of them on startup would probably be cleaner code, less synchronization, at very little memory cost.
	// FIXME check how many bytes we save by lazy init here.
	
	static Map<String, CSSPropertyVerifier> elementVerifiers = new HashMap<String, CSSPropertyVerifier>();
	static HashSet<String> allelementVerifiers=new HashSet<String>(); 
	//Reference http://www.w3.org/TR/CSS2/propidx.html
	static {
		allelementVerifiers.add("azimuth");
		allelementVerifiers.add("background-attachment");
		allelementVerifiers.add("background-color");
		allelementVerifiers.add("background-image");
		allelementVerifiers.add("background-position");
		allelementVerifiers.add("background-repeat");
		allelementVerifiers.add("background");
		allelementVerifiers.add("border-collapse");
		allelementVerifiers.add("border-color");
		allelementVerifiers.add("border-spacing");
		allelementVerifiers.add("border-style");
		allelementVerifiers.add("border-left");
		allelementVerifiers.add("border-top");
		allelementVerifiers.add("border-right");
		allelementVerifiers.add("border-bottom");
		allelementVerifiers.add("border-top-color");
		allelementVerifiers.add("border-right-color");
		allelementVerifiers.add("border-bottom-color");
		allelementVerifiers.add("border-left-color");
		allelementVerifiers.add("border-top-style");
		allelementVerifiers.add("border-right-style");
		allelementVerifiers.add("border-bottom-style");
		allelementVerifiers.add("border-top-width");
		allelementVerifiers.add("border-right-width");
		allelementVerifiers.add("border-bottom-width");
		allelementVerifiers.add("border-left-width");
		allelementVerifiers.add("border-width");
		allelementVerifiers.add("border");
		allelementVerifiers.add("bottom");
		allelementVerifiers.add("caption-side");
		allelementVerifiers.add("clear");
		allelementVerifiers.add("clip");
		allelementVerifiers.add("color");
		allelementVerifiers.add("content");
		allelementVerifiers.add("counter-increment");
		allelementVerifiers.add("counter-reset");
		allelementVerifiers.add("cue-after");
		allelementVerifiers.add("cue-before");
		allelementVerifiers.add("cue");
		allelementVerifiers.add("cursor");
		allelementVerifiers.add("direction");
		allelementVerifiers.add("display");
		allelementVerifiers.add("elevation");
		allelementVerifiers.add("empty-cells");
		allelementVerifiers.add("float");
		allelementVerifiers.add("font-family");
		allelementVerifiers.add("font-size");
		allelementVerifiers.add("font-style");
		allelementVerifiers.add("font-variant");
		allelementVerifiers.add("font-weight");
		allelementVerifiers.add("font");
		allelementVerifiers.add("height");
		allelementVerifiers.add("left");
		allelementVerifiers.add("letter-spacing");
		allelementVerifiers.add("line-height");
		allelementVerifiers.add("list-style-image");
		allelementVerifiers.add("list-style-position");
		allelementVerifiers.add("list-style-type");
		allelementVerifiers.add("list-style");
		allelementVerifiers.add("margin-right");
		allelementVerifiers.add("margin-left");
		allelementVerifiers.add("margin-top");
		allelementVerifiers.add("margin-bottom");
		allelementVerifiers.add("margin");
		allelementVerifiers.add("max-height");
		allelementVerifiers.add("max-width");
		allelementVerifiers.add("min-height");
		allelementVerifiers.add("min-width");
		allelementVerifiers.add("orphans");
		allelementVerifiers.add("outline-color");
		allelementVerifiers.add("outline-style");
		allelementVerifiers.add("outline-width");
		allelementVerifiers.add("outline");
		allelementVerifiers.add("overflow");
		allelementVerifiers.add("padding-top");
		allelementVerifiers.add("padding-right");
		allelementVerifiers.add("padding-bottom");
		allelementVerifiers.add("padding-left");
		allelementVerifiers.add("padding");
		allelementVerifiers.add("page-break-after");
		allelementVerifiers.add("page-break-before");
		allelementVerifiers.add("page-break-inside");
		allelementVerifiers.add("pause-after");
		allelementVerifiers.add("pause-before");
		allelementVerifiers.add("pause");
		allelementVerifiers.add("pitch-range");
		allelementVerifiers.add("pitch");
		allelementVerifiers.add("play-during");
		allelementVerifiers.add("position");
		allelementVerifiers.add("quotes");
		allelementVerifiers.add("richness");
		allelementVerifiers.add("right");
		allelementVerifiers.add("speak-header");
		allelementVerifiers.add("speak-numeral");
		allelementVerifiers.add("speak-punctuation");
		allelementVerifiers.add("speak");
		allelementVerifiers.add("speech-rate");
		allelementVerifiers.add("stress");
		allelementVerifiers.add("table-layout");
		allelementVerifiers.add("text-align");
		allelementVerifiers.add("text-decoration");
		allelementVerifiers.add("text-indent");
		allelementVerifiers.add("text-transform");
		allelementVerifiers.add("top");
		allelementVerifiers.add("unicode-bidi");
		allelementVerifiers.add("vertical-align");
		allelementVerifiers.add("visibility");
		allelementVerifiers.add("voice-family");
		allelementVerifiers.add("volume");
		allelementVerifiers.add("white-space");
		allelementVerifiers.add("widows");
		allelementVerifiers.add("width");
		allelementVerifiers.add("word-spacing");
		allelementVerifiers.add("z-index");


	}

	/*
	 * Array for storing additional Verifier objects for validating Regular expressions in CSS Property value
	 * e.g. [ <color> | transparent]{1,4}. It is explained in detail in CSSPropertyVerifier class
	 */
	static CSSPropertyVerifier[] auxilaryVerifiers=new CSSPropertyVerifier[100];
	static
	{
		/*CSSPropertyVerifier(String[] allowedValues,String[] possibleValues,String expression,boolean onlyValueVerifier)*/
		//for background-position
		auxilaryVerifiers[2]=new CSSPropertyVerifier(new String[]{"left","center","right"},new String[]{"pe","le"},null,true);
		auxilaryVerifiers[3]=new CSSPropertyVerifier(new String[]{"top","center","bottom"},new String[]{"pe","le"},null,true);
		auxilaryVerifiers[4]=new CSSPropertyVerifier(new String[]{"left","center","right"},null,null,true);
		auxilaryVerifiers[5]=new CSSPropertyVerifier(new String[]{"top","center","bottom"},null,null,true);
		//<border-style>
		auxilaryVerifiers[13]=new CSSPropertyVerifier(new String[]{"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},new String[]{"le"},null,true);
		//<border-width>
		auxilaryVerifiers[14]=new CSSPropertyVerifier(new String[]{"thin","medium","thick"},new String[]{"le"},null,true);
		//<border-top-color>
		auxilaryVerifiers[15]=new CSSPropertyVerifier(new String[] {"transparent","inherit"},new String[]{"co"},null,true);
	}
	/* This function loads a verifier object in elementVerifiers.
	 * After the object has been loaded, property name is removed from allelementVerifier.
	 */
	private static void addVerifier(String element)
	{
		if("azimuth".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[0]=new CSSPropertyVerifier(new String[]{"left-side","far-left","left","center-left","center","center-right","right","far-right","right-side"},null,null,true);
			auxilaryVerifiers[1]=new CSSPropertyVerifier(new String[]{"behind"},null,null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"leftwards","rightwards","inherit"},ElementInfo.AURALMEDIA,new String[]{"an"},new String[]{"0a1"}));
			allelementVerifiers.remove(element);
		}
		else if("background-attachment".equalsIgnoreCase(element)){ 
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"scroll","fixed","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("background-color".equalsIgnoreCase(element)){
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"transparent","inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);

		}
		else if("background-image".equalsIgnoreCase(element)){
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.VISUALMEDIA,new String[]{"ur"}));
			allelementVerifiers.remove(element);
		}
		else if("background-position".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"2 3?","4a5"}));
			allelementVerifiers.remove(element);
		}
		else if("background-repeat".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"repeat","repeat-x","repeat-y","no-repeat","inherit"},ElementInfo.VISUALMEDIA,null));
			allelementVerifiers.remove(element);
		}
		else if("background".equalsIgnoreCase(element))
		{      

			//background-attachment
			auxilaryVerifiers[6]=new CSSPropertyVerifier(new String[] {"scroll","fixed","inherit"},null,null,true);
			//background-color
			auxilaryVerifiers[7]=new CSSPropertyVerifier(new String[] {"transparent","inherit"},new String[]{"co"},null,true);
			//background-image
			auxilaryVerifiers[8]=new CSSPropertyVerifier(new String[] {"none","inherit"},new String[]{"ur"},null,true);
			//background-position
			auxilaryVerifiers[9]=new CSSPropertyVerifier(new String[] {"inherit"},null,new String[]{"2 3?","4a5"},true);
			//background-repeat
			auxilaryVerifiers[10]=new CSSPropertyVerifier(new String[] {"repeat","repeat-x","repeat-y","no-repeat","inherit"},null,null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"6a7a8a9a10"}));
			allelementVerifiers.remove(element);
		}

		else if("border-collapse".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.TABLEELEMENTS,new String[] {"collapse","separate","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);

		}
		else if("border-color".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[11]=new CSSPropertyVerifier(new String[] {"transparent"},new String[]{"co"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"},new String[]{"11<1,4>"}));
			allelementVerifiers.remove(element);

		}
		else if("border-spacing".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[12]=new CSSPropertyVerifier(null,new String[]{"le"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.TABLEELEMENTS,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"12 12?"}));
			allelementVerifiers.remove(element);
		}
		else if("border-style".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13<1,4>"}));
			allelementVerifiers.remove(element);
		}
		else if("border-left".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13a14a15"}));
			allelementVerifiers.remove(element);
		}
		else if("border-top".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13a14a15"}));
			allelementVerifiers.remove(element);
		}
		else if("border-right".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13a14a15"}));
			allelementVerifiers.remove(element);
		}
		else if("border-bottom".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13a14a15"}));
			allelementVerifiers.remove(element);
		}
		else if("border-top-color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"transparent","inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);

		}
		else if("border-right-color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"transparent","inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);
		}
		else if("border-bottom-color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"transparent","inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);
		}
		else if("border-left-color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"transparent","inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);
		}
		else if("border-top-style".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("border-right-style".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("border-bottom-style".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("border-left-style".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("border-top-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"thin","medium","thick","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("border-right-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"thin","medium","thick","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("border-bottom-width".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"thin","medium","thick","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("border-left-width".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"thin","medium","thick","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("border-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"14<1,4>"}));
			allelementVerifiers.remove(element);
		}
		else if("border".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"13a14a15"}));
			allelementVerifiers.remove(element);
		}
		else if("bottom".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);

		}
		else if("caption-side".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(new String[]{"caption"},new String[] {"top","bottom","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);

		}
		else if("clear".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"none","left","right","both","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("clip".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"sh"}));
			allelementVerifiers.remove(element);
		}
		else if("color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);

		}
		else if("content".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[16]=new contentPropertyVerifier(new String[]{"open-quote","close-quote","no-open-quote", "no-close-quote" });
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","none","inherit"},ElementInfo.MEDIAARRAY,null,new String[]{"16<1,"+ ElementInfo.UPPERLIMIT+">"}));
			allelementVerifiers.remove(element);
		}
		else if("counter-increment".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[17]=new CSSPropertyVerifier(null,new String[]{"id"},null,true);
			auxilaryVerifiers[18]=new CSSPropertyVerifier(null,new String[]{"in"},null,true);
			auxilaryVerifiers[19]=new CSSPropertyVerifier(null,null,new String[]{"17 18?"},true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.MEDIAARRAY,null,new String[]{"19<1,"+ElementInfo.UPPERLIMIT+">[1,2]"}));
			allelementVerifiers.remove(element);
		}
		else if("counter-reset".equalsIgnoreCase(element))
		{     
			auxilaryVerifiers[20]=new CSSPropertyVerifier(null,new String[]{"id"},null,true);
			auxilaryVerifiers[21]=new CSSPropertyVerifier(null,new String[]{"in"},null,true);
			auxilaryVerifiers[22]=new CSSPropertyVerifier(null,null,new String[]{"20 21?"},true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.MEDIAARRAY,null,new String[]{"22<1,"+ElementInfo.UPPERLIMIT+">[1,2]"}));
			allelementVerifiers.remove(element);
		}
		else if("cue-after".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.AURALMEDIA,new String[]{"ur"}));
			allelementVerifiers.remove(element);

		}
		else if("cue-before".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.AURALMEDIA,new String[]{"ur"}));
			allelementVerifiers.remove(element);
		}
		else if("cue".equalsIgnoreCase(element))
		{      
			//cue-before
			auxilaryVerifiers[23]=new CSSPropertyVerifier(new String[] {"none","inherit"},new String[]{"ur"},null,true);
			//cue-after
			auxilaryVerifiers[24]=new CSSPropertyVerifier(new String[] {"none","inherit"},new String[]{"ur"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.MEDIAARRAY,null,new String[]{"23a24"}));
			allelementVerifiers.remove(element);

		}
		else if("cursor".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[25]=new CSSPropertyVerifier(null,new String[]{"ur"},null,true);
			auxilaryVerifiers[26]=new CSSPropertyVerifier(new String[]{"auto","crosshair","default","pointer","move","e-resize","ne-resize","nw-resize","n-resize","se-resize","sw-resize","s-resize","w-resize","text","wait","help","progress"},null,null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALINTERACTIVEMEDIA,null,new String[]{"25<0,"+ElementInfo.UPPERLIMIT+"> 26"}));

			allelementVerifiers.remove(element);
		}
		else if("direction".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"ltr","rtl","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("display".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inline","block","list-item","run-in","inline-block","table","inline-table","table-row-group","table-header-group","table-footer-group","table-row","table-column-group","table-column","table-cell","table-caption","none","inherit"},ElementInfo.MEDIAARRAY));
			allelementVerifiers.remove(element);
		}
		else if("elevation".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"below","level","above","higher","lower", "inherit"},ElementInfo.AURALMEDIA,new String[]{"an"}));
			allelementVerifiers.remove(element);
		}
		else if("empty-cells".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(new String[]{"th","td"},new String[] {"show","hide","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("float".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"left","right","none","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("font-family".equalsIgnoreCase(element))
		{      

			elementVerifiers.put(element,new FontPropertyVerifier(false));
			allelementVerifiers.remove(element);
		}
		else if("font-size".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"xx-small","x-small","small","medium","large","x-large","xx-large","larger","smaller","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("font-style".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","italic","oblique","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("font-variant".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","small-caps","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("font-weight".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","bold","bolder","lighter","100","200","300","400","500","600","700","800","900","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("font".equalsIgnoreCase(element))
		{      

			//font-style
			auxilaryVerifiers[27]=new CSSPropertyVerifier(new String[] {"normal","italic","oblique","inherit"},null,null,true);
			//font-variant
			auxilaryVerifiers[28]=new CSSPropertyVerifier(new String[] {"normal","small-caps","inherit"},null,null,true);
			//font-weight
			auxilaryVerifiers[29]=new CSSPropertyVerifier(new String[] {"normal","bold","bolder","lighter","100","200","300","400","500","600","700","800","900","inherit"},null,null,true);
			//30-32
			auxilaryVerifiers[30]=new CSSPropertyVerifier(null,null,new String[]{"27a28a29"},true);
			/*
			//font-size
			auxilaryVerifiers[31]=new CSSPropertyVerifier(new String[] {"xx-small","x-small","small","medium","large","x-large","xx-large","larger","smaller","inherit"},null,null,true);
			//line-height
			auxilaryVerifiers[32]=new CSSPropertyVerifier(new String[] {"normal","inherit"},new String[]{"le","pe","re","in"},null,true);

			auxilaryVerifiers[55]=new CSSPropertyVerifier(new String[] {"/"},null,null,true);
			auxilaryVerifiers[56]=new CSSPropertyVerifier(null,null,new String[]{"55 32"},true);
			 */
			auxilaryVerifiers[31]=new FontPartPropertyVerifier();
			//font-family
			auxilaryVerifiers[59]=new FontPropertyVerifier(true);


			/*
			 * old font family
			auxilaryVerifiers[53]=new CSSPropertyVerifier(ElementInfo.FONT_LIST,null,null,true);
			auxilaryVerifiers[54]=new CSSPropertyVerifier(new String[]{"inherit"},null,new String[]{"53 53<0,"+ElementInfo.UPPERLIMIT+">"},true);
			 */
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"caption","icon","menu","message-box","small-caption","status-bar","inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"30<0,1>[1,3] 31<0,1>[1,3] 59"}));
			//elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"caption","icon","menu","message-box","small-caption","status-bar","inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"31<1,1>[1,3]"}));
			allelementVerifiers.remove(element);
		}
		else if("height".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("left".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("letter-spacing".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("line-height".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe","re","in"}));
			allelementVerifiers.remove(element);
		}
		else if("list-style-image".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},ElementInfo.VISUALMEDIA,new String[]{"ur"}));
			allelementVerifiers.remove(element);
		}
		else if("list-style-position".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inside","outside","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("list-style-type".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"disc","circle","square","decimal","decimal-leading-zero","lower-roman","upper-roman","lower-greek","lower-latin","upper-latin","armenian","georgian","lower-alpha","upper-alpha","none","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("list-style".equalsIgnoreCase(element))
		{      
			//list-style-image
			auxilaryVerifiers[33]=new CSSPropertyVerifier(new String[] {"none","inherit"},new String[]{"ur"},null,true);
			//list-style-position
			auxilaryVerifiers[34]=new CSSPropertyVerifier(new String[] {"inside","outside","inherit"},null,null,true);
			//list-style-type
			auxilaryVerifiers[35]=new CSSPropertyVerifier(new String[] {"disc","circle","square","decimal","decimal-leading-zero","lower-roman","upper-roman","lower-greek","lower-latin","upper-latin","armenian","georgian","lower-alpha","upper-alpha","none","inherit"},null,null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"33a34a35"}));
			allelementVerifiers.remove(element);
		}
		else if("margin-right".equalsIgnoreCase(element))
		{      
			//margin-width=Length|Percentage|Auto
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("margin-left".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("margin-top".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("margin-bottom".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("margin".equalsIgnoreCase(element))
		{      
			//margin-width
			auxilaryVerifiers[36]=new CSSPropertyVerifier(new String[] {"auto","inherit"},new String[]{"le","pe"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"36<1,4>"}));
			allelementVerifiers.remove(element);
		}
		else if("max-height".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ALLBUTNONREPLACEDINLINEELEMENTS,new String[] {"none","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("max-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ALLBUTNONREPLACEDINLINEELEMENTS,new String[] {"none","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("min-height".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ALLBUTNONREPLACEDINLINEELEMENTS,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("min-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ALLBUTNONREPLACEDINLINEELEMENTS,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("orphans".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"inherit"},ElementInfo.VISUALPAGEDMEDIA,new String[]{"in"}));
			allelementVerifiers.remove(element);
		}
		else if("outline-color".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"invert", "inherit"},ElementInfo.VISUALINTERACTIVEMEDIA,new String[]{"co"}));
			allelementVerifiers.remove(element);
		}
		else if("outline-style".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},ElementInfo.VISUALINTERACTIVEMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("outline-width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"thin","medium","thick","inherit"},ElementInfo.VISUALINTERACTIVEMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("outline".equalsIgnoreCase(element))
		{      
			//outline-color
			auxilaryVerifiers[37]=new CSSPropertyVerifier(new String[] {"invert", "inherit"},new String[]{"co"},null,true);
			//outline-style
			auxilaryVerifiers[38]=new CSSPropertyVerifier(new String[] {"none","hidden","dotted","dashed","solid","double","groove","ridge","inset","outset", "inherit"},null,null,true);
			//outline-width
			auxilaryVerifiers[39]=new CSSPropertyVerifier(new String[] {"thin","medium","thick","inherit"},new String[]{"le"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[]{"inherit"},ElementInfo.VISUALINTERACTIVEMEDIA,new String[]{"le"},new String[]{"37a38a39"}));
			allelementVerifiers.remove(element);
		}
		else if("overflow".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"visible","hidden","scroll","auto","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("padding-top".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ELEMENTSFORPADDING,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);

		}
		else if("padding-right".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ELEMENTSFORPADDING,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("padding-bottom".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ELEMENTSFORPADDING,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("padding-left".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ELEMENTSFORPADDING,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("padding".equalsIgnoreCase(element))
		{      
			//padding-width
			auxilaryVerifiers[40]=new CSSPropertyVerifier(new String[] {"inherit"},new String[]{"le","pe"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.ELEMENTSFORPADDING,new String[] {"inherit"},ElementInfo.VISUALMEDIA,null,new String[]{"40<1,4>"}));
			allelementVerifiers.remove(element);
		}
		else if("page-break-after".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"auto","always","avoid","left","right","inherit"},ElementInfo.VISUALPAGEDMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("page-break-before".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"auto","always","avoid","left","right","inherit"},ElementInfo.VISUALPAGEDMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("page-break-inside".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"auto","avoid","inherit"},ElementInfo.VISUALPAGEDMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("pause-after".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.AURALMEDIA,new String[]{"ti","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("pause-before".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.AURALMEDIA,new String[]{"ti","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("pause".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[41]=new CSSPropertyVerifier(new String[] {"inherit"},new String[]{"ti","pe"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},null,null,new String[]{"41<1,2>"}));
			allelementVerifiers.remove(element);

		}
		else if("pitch-range".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.AURALMEDIA,new String[]{"in","re"}));
			allelementVerifiers.remove(element);
		}
		else if("pitch".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"x-low","low","medium","high","x-high","inherit"},ElementInfo.AURALMEDIA,new String[]{"fr"}));
			allelementVerifiers.remove(element);

		}
		else if("play-during".equalsIgnoreCase(element))
		{      

			auxilaryVerifiers[42]=new CSSPropertyVerifier(null,new String[]{"ur"},null,true);
			auxilaryVerifiers[43]=new CSSPropertyVerifier(new String[]{"mix"},null,null,true);
			auxilaryVerifiers[44]=new CSSPropertyVerifier(new String[]{"repeat"},null,null,true);
			auxilaryVerifiers[45]=new CSSPropertyVerifier(null,null,new String[]{"43a44"},true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","none","inherit"},ElementInfo.AURALMEDIA,null,new String[]{"42 45<0,1>[1,2]"}));
			allelementVerifiers.remove(element);


		}
		else if("position".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"static","relative","absolute","fixed","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("quotes".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[46]=new CSSPropertyVerifier(null,new String[]{"st"},null,true);
			auxilaryVerifiers[47]=new CSSPropertyVerifier(null,null,new String[]{"46 46"},true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none","inherit"},null,ElementInfo.VISUALMEDIA,new String[]{"47<1,"+ ElementInfo.UPPERLIMIT+">[2,2]"}));
			allelementVerifiers.remove(element);
		}
		else if("richness".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.AURALMEDIA,new String[]{"re","in"}));
			allelementVerifiers.remove(element);
		}
		else if("right".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("speak-header".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(new String[]{"th","td"},new String[] {"once","always","inherit"},ElementInfo.AURALMEDIA));
			allelementVerifiers.remove(element);

		}
		else if("speak-numeral".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"digits","continuous","inherit"},ElementInfo.AURALMEDIA));
			allelementVerifiers.remove(element);

		}
		else if("speak-punctuation".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"code", "none","inherit"},ElementInfo.AURALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("speak".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","none","spell-out","inherit"},ElementInfo.AURALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("speech-rate".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"x-slow","slow","medium","fast","x-fast","faster","slower","inherit"},ElementInfo.AURALMEDIA,new String[]{"re","in"}));
			allelementVerifiers.remove(element);
		}
		else if("stress".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},ElementInfo.AURALMEDIA,new String[]{"re","in"}));
			allelementVerifiers.remove(element);
		}
		else if("table-layout".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier( concat(ElementInfo.INLINEELEMENTS,ElementInfo.TABLEELEMENTS),new String[] {"auto","fixed","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("text-align".equalsIgnoreCase(element))
		{
			elementVerifiers.put(element,new CSSPropertyVerifier( concat(concat(ElementInfo.INLINEELEMENTS,ElementInfo.TABLEELEMENTS),ElementInfo.BLOCKLEVELELEMENTS),new String[] {"left","right", "center", "justify" ,"inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("text-decoration".equalsIgnoreCase(element))
		{      
			auxilaryVerifiers[48]=new CSSPropertyVerifier(new String[]{"underline"},null,null,true);
			auxilaryVerifiers[49]=new CSSPropertyVerifier(new String[]{"overline"},null,null,true);
			auxilaryVerifiers[50]=new CSSPropertyVerifier(new String[]{"line-through"},null,null,true);
			auxilaryVerifiers[51]=new CSSPropertyVerifier(new String[]{"blink"},null,null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"none" ,"inherit"},null,ElementInfo.VISUALMEDIA,new String[]{"48a49a50a51"}));
			allelementVerifiers.remove(element);

		}
		else if("text-indent".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier( concat(concat(ElementInfo.INLINEELEMENTS,ElementInfo.TABLEELEMENTS),ElementInfo.BLOCKLEVELELEMENTS),new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("text-transform".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier( ElementInfo.HTMLELEMENTSARRAY,new String[] {"capitalize","uppercase","lowercase","none","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("top".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier( ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("unicode-bidi".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier( ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal", "embed", "bidi-override","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("vertical-align".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(concat(ElementInfo.INLINEELEMENTS,new String[]{"th","td"}),new String[] {"baseline","sub","super","top","text-top","middle","bottom","text-bottom","inherit"},ElementInfo.VISUALMEDIA,new String[]{"pe","le"}));
			allelementVerifiers.remove(element);
		}
		else if("visibility".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"visible","hidden","collapse","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("voice-family".equalsIgnoreCase(element))
		{      
			// FIXME implement properly, reuse the font code.
			//generic-voice & //specific-voice
			auxilaryVerifiers[52]=new CSSPropertyVerifier(new String[]{"male","female","child"},new String[]{"st"},null,true);
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"inherit"},null,ElementInfo.AURALMEDIA,new String[]{"52<0,"+ElementInfo.UPPERLIMIT+"> 52"}));
			allelementVerifiers.remove(element);
			//53 54 55 56 59
		}
		else if("volume".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"silent","x-soft","soft","medium","loud","x-loud","inherit"},ElementInfo.AURALMEDIA,new String[]{"re","le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("white-space".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","pre","nowrap","pre-wrap","pre-line","inherit"},ElementInfo.VISUALMEDIA));
			allelementVerifiers.remove(element);
		}
		else if("widows".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.BLOCKLEVELELEMENTS,new String[] {"inherit"},ElementInfo.VISUALMEDIA,new String[]{"in"}));
			allelementVerifiers.remove(element);
		}
		else if("width".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le","pe"}));
			allelementVerifiers.remove(element);
		}
		else if("word-spacing".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"normal","inherit"},ElementInfo.VISUALMEDIA,new String[]{"le"}));
			allelementVerifiers.remove(element);
		}
		else if("z-index".equalsIgnoreCase(element))
		{      
			elementVerifiers.put(element,new CSSPropertyVerifier(ElementInfo.HTMLELEMENTSARRAY,new String[] {"auto","inherit"},ElementInfo.VISUALMEDIA,new String[]{"in"}));
			allelementVerifiers.remove(element);
		}


	}


	/*
	 * This function returns the Verifier for a property. If it is not already loaded in the elementVerifier, then it is loaded and then returned to the caller.
	 * FIXME: Lazy init probably doesn't make sense, but while we are initting lazily, we need to hold a lock here.
	 */
	private synchronized static CSSPropertyVerifier getVerifier(String element)
	{
		element=element.toLowerCase();
		if(elementVerifiers.get(element)!=null)
			return elementVerifiers.get(element);
		else if(allelementVerifiers.contains(element))
		{
			addVerifier(element);
			return elementVerifiers.get(element);
		}
		else
			return null;
	}
	/*
	 * This function accepts media, list of HTML elements, CSS property and value and determines whether it is valid or not.
	 * @media print
	 * {
	 * h1, h2 , h4, h4 {font-size: 10pt}
	 * }
	 * media: print
	 * elements: [h1, h2, h3, h4]
	 * token: font-size
	 * value: 10pt
	 *
	 */
	private boolean verifyToken(String[] media,String[] elements,String token,ParsedWord[] words)
	{
		if(words == null) return false;
		if(logDEBUG) log("verifyToken for "+CSSPropertyVerifier.toString(words)+" for "+token);
		CSSPropertyVerifier obj=getVerifier(token);
		if(obj==null)
		{
			return false;
		}
		int important = checkImportant(words);
		if(important > 0) {
			if(words.length == important) return true; // Eh? !important on its own!
			ParsedWord[] newWords = new ParsedWord[words.length-important];
			System.arraycopy(words, 0, newWords, 0, newWords.length);
			words = newWords;
		}
		return obj.checkValidity(media, elements, words, cb);

	}
	
	private int checkImportant(ParsedWord[] words) {
		if(words.length == 0) return 0;
		if(words.length >= 1 && words[words.length-1] instanceof SimpleParsedWord) {
			if(((SimpleParsedWord)words[words.length-1]).original.equalsIgnoreCase("!important")) return 1;
		}
		if(words.length >= 2 && words[words.length-1] instanceof ParsedIdentifier && words[words.length-2] instanceof SimpleParsedWord) {
			if(((SimpleParsedWord)words[words.length-2]).original.equals("!") &&
				((ParsedIdentifier)words[words.length-1]).original.equalsIgnoreCase("important"))
				return 2;
		}
		return 0;
	}

	/*
	 * This function accepts an HTML element(along with class name, ID, pseudo class and attribute selector) and determines whether it is valid or not.
	 */
	public String HTMLelementVerifier(String elementString)
	{
		if(logDEBUG) log("varifying element/selector: \""+elementString+"\"");
		String HTMLelement="",pseudoClass="",className="",id="";
		boolean isValid=true;
		StringBuffer fBuffer=new StringBuffer();
		ArrayList<String> attSelections = null;
		while(elementString.indexOf('[')!=-1 && elementString.indexOf(']')!=-1 && (elementString.indexOf('[')<elementString.indexOf(']')))
		{
			String attSelection=elementString.substring(elementString.indexOf('[')+1,elementString.indexOf(']')).trim();
			StringBuffer buf=new StringBuffer(elementString);
			buf.delete(elementString.indexOf('['), elementString.indexOf(']')+1);
			elementString=buf.toString();
			if(logDEBUG) log("attSelection="+attSelection+"  elementString="+elementString);
			if(attSelections == null) attSelections = new ArrayList<String>();
			attSelections.add(attSelection);
		}
		if(elementString.indexOf(':')!=-1)
		{
			int index=elementString.indexOf(':');
			if(index!=elementString.length()-1)
			{
				pseudoClass=elementString.substring(index+1,elementString.length()).trim();
				HTMLelement=elementString.substring(0,index).trim();
				if(logDEBUG) log("pseudoclass="+pseudoClass+" HTMLelement="+HTMLelement);
			}
			else
			{
				HTMLelement=elementString.trim();
			}
		}
		else
			HTMLelement=elementString.trim();

		if(HTMLelement.indexOf('.')!=-1)
		{
			int index=HTMLelement.indexOf('.');
			if(index!=HTMLelement.length()-1)
			{
				className=HTMLelement.substring(index+1,HTMLelement.length()).trim();
				HTMLelement=HTMLelement.substring(0,index).trim();
				if(logDEBUG) log("class="+className+" HTMLelement="+HTMLelement);
			}

		}
		else if(HTMLelement.indexOf('#')!=-1)
		{
			int index=HTMLelement.indexOf('#');
			if(index!=HTMLelement.length()-1)
			{
				id=HTMLelement.substring(index+1,HTMLelement.length()).trim();
				HTMLelement=HTMLelement.substring(0,index).trim();
				if(logDEBUG) log("id="+id+" element="+HTMLelement);
			}

		}

		if("*".equals(HTMLelement) || (ElementInfo.isValidHTMLTag(HTMLelement.toLowerCase())) || ("".equals(HTMLelement.trim()) && (className!="" || id!="" || attSelections!=null || pseudoClass!="")))
		{
			if(className!="")
			{
				// Note that the definition of isValidName() allows chained classes because it allows . in class names.
				if(!ElementInfo.isValidName(className))
					isValid=false;
			}
			else if(id!="")
			{
				if(!ElementInfo.isValidName(id))
					isValid=false;
			}

			if(isValid && pseudoClass!="")
			{
				if(!ElementInfo.isValidPseudoClass(pseudoClass))
					isValid=false;
			}

			if(isValid && attSelections!=null)
			{
				String[] attSelectionParts;
				
				for(String attSelection : attSelections) {
					if(attSelection.indexOf("|=")!=-1)
					{
						attSelectionParts=new String[2];
						attSelectionParts[0]=attSelection.substring(0,attSelection.indexOf("|="));
						attSelectionParts[1]=attSelection.substring(attSelection.indexOf("|=")+2,attSelection.length());
					}
					else if(attSelection.indexOf("~=")!=-1) {
						attSelectionParts=new String[2];
						attSelectionParts[0]=attSelection.substring(0,attSelection.indexOf("~="));
						attSelectionParts[1]=attSelection.substring(attSelection.indexOf("~=")+2,attSelection.length());
					} else if(attSelection.indexOf('=') != -1){
						attSelectionParts=new String[2];
						attSelectionParts[0]=attSelection.substring(0,attSelection.indexOf("="));
						attSelectionParts[1]=attSelection.substring(attSelection.indexOf("=")+1,attSelection.length());
					} else {
						attSelectionParts=new String[] { attSelection };
					}
					
					//Verifying whether each character is alphanumeric or _
					if(logDEBUG) log("HTMLelementVerifier length of attSelectionParts="+attSelectionParts.length);
					
					if(attSelectionParts[0].length()==0)
						isValid=false;
					else
					{
						char c=attSelectionParts[0].charAt(0);
						if(!((c>='a' && c<='z') || (c>='A' && c<='Z')))
							isValid=false;
						for(int i=1;i<attSelectionParts[0].length();i++)
						{
							if(!((c>='a' && c<='z') || (c>='A' && c<='Z') || c=='_' || c=='-'))
								isValid=false;
						}
					}
					
					if(attSelectionParts.length > 1) {
						// What about the right hand side?
						// The grammar says it's an IDENT.
						if(logDEBUG) log("RHS is \""+attSelectionParts[1]+"\"");
						if(!(ElementInfo.isValidIdentifier(attSelectionParts[1]) || ElementInfo.isValidStringWithQuotes(attSelectionParts[1]))) isValid = false;
					}
				}
			}


			if(isValid)
			{
				fBuffer.append(HTMLelement);
				if(className!="")
					fBuffer.append("."+className);
				else if(id!="")
					fBuffer.append("#"+id);
				if(pseudoClass!="")
					fBuffer.append(":"+pseudoClass);
				if(attSelections!=null) {
					for(String attSelection:attSelections)
						fBuffer.append("["+attSelection+"]");
				}
				return fBuffer.toString();
			}
		}

		return null;
	}
	/*
	 * This function works with different operators, +, >, " " and verifies each HTML element with HTMLelementVerifier(String elementString)
	 * e.g. div > p:first-child
	 * This would call HTMLelementVerifier with div and p:first-child
	 */
	public String recursiveSelectorVerifier(String selectorString)
	{
		if(logDEBUG) log("selector: \""+selectorString+"\"");
		selectorString=selectorString.trim();
		
		// Parse but don't tokenise.
		
		int plusIndex,gtIndex,spaceIndex;
		plusIndex = Integer.MAX_VALUE;
		gtIndex = Integer.MAX_VALUE;
		spaceIndex = Integer.MAX_VALUE;
		int index = -1;
		char selector = 0;
		
		char c;
		char quoting = 0;
		boolean escaping = false;
		boolean eatLF = false;
		int escapedDigits = 0;
		for(int i=0;i<selectorString.length();i++) {
			c = selectorString.charAt(i);
			if(c == '+' && quoting == 0 && !escaping) {
				if(index == -1 || index == i-1 && selector == ' ') {
					plusIndex = i;
					index = i;
					selector = c;
				}
			} else if(c == '>' && quoting == 0 && !escaping) {
				if(index == -1 || index == i-1 && selector == ' ') {
					gtIndex = i;
					index = i;
					selector = c;
				}
			} else if(c == ' ' && quoting == 0 && !escaping) {
				if(index == -1 || index == i-1 && selector == ' ') {
					spaceIndex = i;
					index = i;
					selector = c;
				}
			} else if(c == '\'' && quoting == 0 && !escaping) {
				quoting = c;
			} else if(c == '\"' && quoting == 0 && !escaping) {
				quoting = c;
			} else if(c == quoting && !escaping) {
				quoting = 0;
			} else if(c == '\n' && eatLF) {
				// Ok
				escaping = false;
				eatLF = false;
			} else if((c == '\r' || c == '\n' || c == '\f') && !(quoting != 0 && escaping)) {
				// No newlines unless in a string *and* quoted!
				if(logDEBUG) log("no newlines unless in a string *and* quoted at index "+i);
				return null;
			} else if(c == '\r' && escaping && escapedDigits == 0) {
				escaping = false;
				eatLF = true;
			} else if((c == '\n' || c == '\f') && escaping) {
				if(escapedDigits == 0)
					escaping = false;
				else {
					if(logDEBUG) log("invalid newline escaping at char "+i);
					return null; // Invalid
				}
			} else if(escaping && ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
				escapedDigits++;
				if(escapedDigits == 6)
					escaping = false;
			} else if(escaping && escapedDigits > 0 && (" \t\r\n\f".indexOf(c) != -1)) {
				escaping = false;
				if(c == '\r') eatLF = true;
			} else if(c == '\\' && !escaping) {
				escaping = true;
			} else if(c == '\\' && escaping && escapedDigits > 0) {
				if(logDEBUG) log("backslash but already escaping with digits at char "+i);
				return null; // Invalid
			} else if(c == '\\' && escaping) {
				escaping = false;
			} else if(escaping) {
				// Any other character can be escaped.
				escaping = false;
			}
			eatLF = false;
		}
		
		if(logDEBUG) log("index="+index+" quoting="+quoting+" selector="+selector+" for \""+selectorString+"\"");
		
		if(quoting != 0) return null; // Mismatched quotes
		
		if(index == -1)
			return HTMLelementVerifier(selectorString);
		
		String[] parts=new String[2];

		parts[0]=selectorString.substring(0,index).trim();
		parts[1]=selectorString.substring(index+1,selectorString.length()).trim();
		if(logDEBUG) log("recursiveSelectorVerifier parts[0]=" + parts[0]+" parts[1]="+parts[1]);
		parts[0]=HTMLelementVerifier(parts[0]);
		parts[1]=recursiveSelectorVerifier(parts[1]);
		if(parts[0]!=null && parts[1]!=null)
			return parts[0]+selector+parts[1];
		else
			return null;

	}



	// main function
	public void parse() throws IOException {

		final int STATE1=1; //State corresponding to @page,@media etc
		final int STATE2=2; //State corresponding to HTML element like body
		final int STATE3=3; //State corresponding to CSS properties

		/* e.g.
		 * STATE1
		 * @media screen {
		 * STATE2	STATE3
		 * h2 		{text-align:left;}
		 * }
		 */
		final int STATECOMMENT=4;
		final int STATE1INQUOTE=5;
		final int STATE2INQUOTE=6;
		final int STATE3INQUOTE=7;
		char currentQuote='"';
		int stateBeforeComment=0;
		int currentState=1;
		boolean isState1Present=false;
		String elements[]=null;
		StringBuffer filteredTokens=new StringBuffer();
		StringBuffer buffer=new StringBuffer();
		int openBraces=0;
		String defaultMedia="screen";
		String[] currentMedia=new String[] {defaultMedia};
		String propertyName="",propertyValue="";
		boolean ignoreElementsS1=false,ignoreElementsS2=false,ignoreElementsS3=false,closeIgnoredS1=false,closeIgnoredS2=false;
		int x;
		char c=0,prevc=0;
		boolean s2Comma=false;
		boolean canImport=true; //import statement can occur only in the beginning

		String whitespaceAfterColon = "";
		String whitespaceBeforeProperty = "";
		
		boolean charsetPossible = true;
		boolean bomPossible = true;
		int openBracesStartingS3 = 0;
		boolean forPage = false;
		
		if(isInline) {
			currentState = STATE3;
		}
		
		while(true)
		{
			try
			{
				x=r.read();
			}
			catch(IOException e)
			{
				throw e;
			}

			if(x==-1)
			{
				break;
			}
			if(x == (char) 0xFEFF) {
				if(bomPossible) {
					// BOM
					if(logDEBUG) log("Ignoring BOM");
					w.write(x);
				}
				continue;
			}
			bomPossible = false;
			prevc=c;
			c=(char) x;
			if(logDEBUG) log("Read: "+c);
			if(prevc=='/' && c=='*' && currentState!=STATE1INQUOTE && currentState!=STATE2INQUOTE && currentState!=STATE3INQUOTE&&currentState!=STATECOMMENT)
			{
				stateBeforeComment=currentState;
				currentState=STATECOMMENT;
				if(buffer.charAt(buffer.length()-1)=='/')
				{
					buffer.deleteCharAt(buffer.length()-1);
				}
				if(logDEBUG) log("Comment detected: buffer="+buffer);
			}
			if(c == 0)
				continue; // Strip nulls
			switch(currentState)
			{
			case STATE1: 
				switch(c){
				case '\n':
				case ' ':
				case '\t':
					buffer.append(c);
					if(logDEBUG) log("STATE1 CASE whitespace: "+c);
					break;

				case '@':
					if(prevc != '\\') {
						isState1Present=true;
						if(logDEBUG) log("STATE1 CASE @: "+c);
					} // Else leave it in buffer, encoded.
					buffer.append(c);
					break;

				case '{':
					charsetPossible=false;
					if(stopAtDetectedCharset)
						return;
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					openBraces++;
					isState1Present=false;
					
					int i = 0;
					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					String braceSpace = buffer.substring(0, i);
					buffer.delete(0, i);
					if(buffer.length() > 4 && buffer.substring(0, 4).equals("<!--")) {
						braceSpace +=buffer.substring(0, 4);
						if(" \t\r\n".indexOf(buffer.charAt(4))==-1) {
							Logger.error(this, "<!-- not followed by whitespace!");
							return;
						}
						buffer.delete(0, 4);
						for(i=0;i<buffer.length();i++) {
							char c1 = buffer.charAt(i);
							if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
								continue;
							break;
						}
						braceSpace += buffer.substring(0, i);
						buffer.delete(0, i);
					}
					for(i=buffer.length()-1;i>=0;i--) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					i++;
					String postSpace = buffer.substring(i);
					buffer.setLength(i);
					String orig = buffer.toString().trim();
					ParsedWord[] parts=split(orig);
					if(logDEBUG) log("Split: "+CSSPropertyVerifier.toString(parts));
					buffer.setLength(0);
					boolean valid = false;
					if(parts.length<1)
					{
						ignoreElementsS1=true;
						if(logDEBUG) log("STATE1 CASE {: Does not have one part. ignoring "+buffer.toString());
						valid = false;
					}
					else if(parts[0] instanceof SimpleParsedWord && "@media".equals(((SimpleParsedWord)parts[0]).original.toLowerCase()))
					{
						if(parts.length<2)
						{
							ignoreElementsS1=true;
							if(logDEBUG) log("STATE1 CASE {: Does not have two parts. ignoring "+buffer.toString());
							valid = false;
						} else {
						ArrayList<String> medias = commaListFromIdentifiers(parts, 1);
						if(medias != null && medias.size() > 0) {
							for(i=0;i<medias.size();i++) {
								if(!FilterUtils.isMedia(medias.get(i))) {
									// Unrecognised media, don't pass it.
									medias.remove(i);
									i--; // Don't skip next
								}
							}
						}
						if(medias != null && medias.size() > 0) {
							filteredTokens.append(braceSpace);
							filteredTokens.append("@media ");
							boolean first = true;
							for(String media : medias) {
								if(!first) filteredTokens.append(", ");
								first = false;
								filteredTokens.append(media);
							}
							filteredTokens.append(postSpace);
							filteredTokens.append("{");
							valid = true;
							currentMedia = medias.toArray(new String[medias.size()]);
						}
						}
					} else if(parts[0] instanceof SimpleParsedWord && "@page".equals(((SimpleParsedWord)parts[0]).original.toLowerCase()))
						{
						if(parts.length == 0) {
							valid = true;
						} else {
							valid = true;
							for(int j=1;j<parts.length;j++) {
								if(!(parts[j] instanceof SimpleParsedWord)) {
									valid = false;
									break;
								} else {
									String s = ((SimpleParsedWord)parts[j]).original;
									if(!(s.equalsIgnoreCase(":left") || s.equalsIgnoreCase(":right") || s.equals(":first"))) {
										valid = false;
										break;
									}
								}
							}
						}
						if(valid) {
							forPage = true;
							filteredTokens.append(braceSpace);
							filteredTokens.append(orig);
							filteredTokens.append(postSpace);
							filteredTokens.append("{");
						}
					}
					if(!valid)
					{
						ignoreElementsS1=true;
						// No valid media types.
						if(logDEBUG) log("STATE1 CASE {: Failed verification test. ignoring "+buffer.toString());
					} else {
						w.write(filteredTokens.toString());
						filteredTokens.setLength(0);
					}
					buffer.setLength(0);
					s2Comma=false;
					if(forPage) {
						currentState=STATE3;
						openBracesStartingS3 = openBraces;
					} else {
						currentState=STATE2;
					}
					buffer.setLength(0);
					break;
				case ';':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					if(logDEBUG) log("buffer in state 1 ; : \""+buffer.toString()+"\"");
					//should be @import

					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					w.write(buffer.substring(0, i));
					buffer.delete(0, i);
					
					if(buffer.length() > 4 && buffer.substring(0, 4).equals("<!--")) {
						w.write(buffer.substring(0, 4));
						if(" \t\r\n".indexOf(buffer.charAt(4))==-1) {
							Logger.error(this, "<!-- not followed by whitespace!");
							return;
						}
						buffer.delete(0, 4);
						for(i=0;i<buffer.length();i++) {
							char c1 = buffer.charAt(i);
							if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
								continue;
							break;
						}
						w.write(buffer.substring(0, i));
						buffer.delete(0, i);
					}
					
					if(canImport && !ignoreElementsS1 && buffer.toString().contains("@import"))
					{
						if(logDEBUG) log("STATE1 CASE ;statement="+buffer.toString());
						
						String strbuffer=buffer.toString().trim();
						int importIndex=strbuffer.toLowerCase().indexOf("@import");
						if("".equals(strbuffer.substring(0,importIndex).trim()))
						{
							String str1=strbuffer.substring(importIndex+7,strbuffer.length());
							ParsedWord[] strparts=split(str1);
							boolean broke = true;
							if(strparts != null && strparts.length > 0 && (strparts[0] instanceof ParsedURL || strparts[0] instanceof ParsedString)) {
								broke = false;
								String uri;
								if(strparts[0] instanceof ParsedString) {
									uri = ((ParsedString)strparts[0]).getDecoded();
								} else {
									uri = ((ParsedURL)strparts[0]).getDecoded();
								}
								ArrayList<String> medias = commaListFromIdentifiers(strparts, 1);
									
								if(medias != null) { // None gives [0], broke gives null
									StringBuffer output = new StringBuffer();
									output.append("@import url(\"");
									try {
										// Add ?maybecharset= even though there might be a ?type= with a charset, we will ignore maybecharset if there is.
										// We behave similarly in <link rel=stylesheet...> if there is a ?type= in the URL.
										String s = cb.processURI(uri, "text/css");
										if(passedCharset != null) {
											if(s.indexOf('?') == -1)
												s += "?maybecharset="+passedCharset;
											else
												s += "&maybecharset="+passedCharset;
										}
										output.append(s);
										output.append("\")");
										boolean first = true;
										for(String media : medias) {
											if(FilterUtils.isMedia(media)) {
												if(!first) output.append(", ");
												else output.append(' ');
												first = false;
												output.append(media);
											}
										}
										output.append(";");
										w.write(output.toString());
									} catch (CommentException e) {
										// Don't write anything
									}
								}
							}
						}
					} else if(charsetPossible && buffer.toString().startsWith("@charset ")) {
						String s = buffer.delete(0, "@charset ".length()).toString();
						s = removeOuterQuotes(s);
						detectedCharset = s;
						if(logDEBUG) log("Detected charset: \""+detectedCharset+"\"");
						if(!Charset.isSupported(detectedCharset)) {
							Logger.normal(this, "Charset not supported: "+detectedCharset);
							throw new UnsupportedCharsetInFilterException("Charset not supported: "+detectedCharset);
						}
						if(stopAtDetectedCharset) return;
						if(passedCharset != null && !detectedCharset.equalsIgnoreCase(passedCharset)) {
							Logger.normal(this, "Detected charset \""+detectedCharset+"\" differs from passed in charset \""+passedCharset+"\"");
							throw new IOException("Detected charset differs from passed in charset");
						}
						w.write("@charset \""+detectedCharset+"\";");
					}
					isState1Present=false;
					ignoreElementsS1 = false;
					closeIgnoredS1 = false;
					buffer.setLength(0);
					charsetPossible=false;
					break;
				case '"':
				case '\'':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					buffer.append(c);
					currentState=STATE1INQUOTE;
					currentQuote=c;
					break;
				default:
					buffer.append(c);
				if(!isState1Present)
				{
					String s = buffer.toString().trim();
					if(!(s.equals("") || s.equals("<") || s.equals("<!") || s.equals("<!-") || s.equals("<!--")))
						currentState=STATE2;	
				}
				if(logDEBUG) log("STATE1 default CASE: "+c);
				break;

				}
				break;

			case STATE1INQUOTE:
				if(logDEBUG) log("STATE1INQUOTE: "+c);
				switch(c)
				{
				case '"':
					if(currentQuote=='"' && prevc!='\\')
						currentState=STATE1;
					buffer.append(c);
					break;
				case '\'':
					if(currentQuote=='\'' && prevc!='\\')
						currentState=STATE1;
					buffer.append(c);
					break;
				case '\n':
					if(prevc == '\r') {
						break;
					}
					// Otherwise same as \r ...
				case '\f':
				case '\r':
					if(prevc == '\\') {
						ignoreElementsS1 = true;
						closeIgnoredS1 = true;
						currentState = STATE1;
						break;
					} else {
						// Wipe out the \ as well.
						buffer.setLength(buffer.length()-1);
						break;
					}
				default:
					buffer.append(c);
				break;
				}
				break;


			case STATE2:
				canImport=false;
				charsetPossible=false;
				if(stopAtDetectedCharset)
					return;
				switch(c)
				{
				case '{':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					
					int i = 0;
					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					if(logDEBUG) log("Appending whitespace in state2: \""+buffer.substring(0,i)+"\"");
					String ws = buffer.substring(0, i);
					buffer.delete(0, i);
					
					if(buffer.length() > 4 && buffer.substring(0, 4).equals("<!--")) {
						ws+=buffer.substring(0, 4);
						if(" \t\r\n".indexOf(buffer.charAt(4))==-1) {
							Logger.error(this, "<!-- not followed by whitespace!");
							return;
						}
						buffer.delete(0, 4);
						for(i=0;i<buffer.length();i++) {
							char c1 = buffer.charAt(i);
							if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
								continue;
							break;
						}
						ws+=buffer.substring(0, i);
						buffer.delete(0, i);
					}
					
					openBraces++;
					if(buffer.toString().trim()!="")
					{
						String filtered=recursiveSelectorVerifier(buffer.toString());
						if(filtered!=null)
						{
							if(s2Comma)
							{
								filteredTokens.append(",");
								s2Comma=false;
							}
							filteredTokens.append(ws);
							filteredTokens.append(filtered);
							filteredTokens.append(" {");
						}
						else
						{
							ignoreElementsS2=true;
							// If there was a comma, filteredTokens may contain some tokens.
							// These are invalid, as per the spec: we wipe the whole selector out.
							// Also, not wiping filteredTokens here does bad things: 
							// we would write the filtered tokens, without the { or }, so we end up prepending it to the next rule, which is not what we want as it changes the next rule's meaning.
							filteredTokens.setLength(0);
						}
						if(logDEBUG) log("STATE2 CASE { filtered elements"+filtered);
					}
					currentState=STATE3;
					openBracesStartingS3 = openBraces;
					if(logDEBUG) log("STATE2 -> STATE3, openBracesStartingS3 = "+openBracesStartingS3);
					buffer.setLength(0);
					break;

				case ',':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					if(logDEBUG) log("Appending whitespace in state2: \""+buffer.substring(0,i)+"\"");
					ws = buffer.substring(0, i);
					buffer.delete(0, i);
					
					if(!s2Comma) {
						if(buffer.length() > 4 && buffer.substring(0, 4).equals("<!--")) {
							filteredTokens.append(buffer.substring(0, 4));
							if(" \t\r\n".indexOf(buffer.charAt(4))==-1) {
								Logger.error(this, "<!-- not followed by whitespace!");
								return;
							}
							buffer.delete(0, 4);
							for(i=0;i<buffer.length();i++) {
								char c1 = buffer.charAt(i);
								if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
									continue;
								break;
							}
							filteredTokens.append(buffer.substring(0, i));
							buffer.delete(0, i);
						}
					}
					
					
					String filtered=recursiveSelectorVerifier(buffer.toString().trim());
					if(logDEBUG) log("STATE2 CASE , filtered elements"+filtered);
					if(filtered!=null)
					{
						if(s2Comma)
							filteredTokens.append(",");
						else
							s2Comma=true;
						filteredTokens.append(ws);
						filteredTokens.append(filtered);
					}
					buffer.setLength(0);
					break;	


				case '}':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					if(openBraces > 0 && !ignoreElementsS1) {
						openBraces--;
						// ignoreElementsS2 is irrelevant here, we are not *adding to* filteredTokens.
						if(openBraces >= 0)
							filteredTokens.append('}');
						else
							openBraces = 0;
						if(logDEBUG) log("Writing \""+filteredTokens+"\"");
						w.write(filteredTokens.toString());
					} else {
						if(openBraces > 0) openBraces--;
						// Ignore.
						// We are going back to STATE1, so reset ignoreElementsS1
						ignoreElementsS1 = false;
					}
					filteredTokens.setLength(0);
					buffer.setLength(0);
					currentMedia=new String[] {defaultMedia};
					isState1Present=false;
					currentState=STATE1;
					if(isInline) return;
					if(logDEBUG) log("STATE2 CASE }: "+c);
					break;

				case '"':
				case '\'':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					buffer.append(c);
					currentState=STATE2INQUOTE;
					currentQuote=c;
					break;

				default:
					buffer.append(c);
				if(logDEBUG) log("STATE2 default CASE: "+c);
				break;
				}
				break;

			case STATE2INQUOTE:
				if(logDEBUG) log("STATE2INQUOTE: "+c);
				charsetPossible=false;
				switch(c)
				{
				case '"':
					if(currentQuote=='"'&& prevc!='\\')
						currentState=STATE2;
					buffer.append(c);
					break;
				case '\'':
					if(currentQuote=='\''&& prevc!='\\')
						currentState=STATE2;
					buffer.append(c);
					break;
				case '\n':
					if(prevc == '\r') {
						break;
					}
					// Otherwise same as \r ...
				case '\f':
				case '\r':
					if(prevc != '\\') {
						ignoreElementsS2 = true;
						closeIgnoredS2 = true;
						currentState = STATE2;
						break;
					} else {
						// Wipe out the \ as well.
						buffer.setLength(buffer.length()-1);
						break;
					}
				default:
					buffer.append(c);
				break;
				}
				break;

			case STATE3:
				charsetPossible=false;
				if(stopAtDetectedCharset)
					return;
				switch(c)
				{
				case ':':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					if(openBraces > openBracesStartingS3) {
						// Correctly tokenise bogus properties containing {}'s, see CSS2.1 section 4.1.6.
						buffer.append(c);
						if(logDEBUG) log("openBraces now "+openBraces+" not moving on because openBracesStartingS3="+openBracesStartingS3+" in S3");
						break;
					}
					int i = 0;
					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					if(logDEBUG) log("Appending whitespace: "+buffer.substring(0,i));
					whitespaceBeforeProperty = buffer.substring(0, i);
					propertyName=buffer.delete(0, i).toString().trim();
					if(logDEBUG) log("Property name: "+propertyName);
					buffer.setLength(0);
					if(logDEBUG) log("STATE3 CASE :: "+c);
					break;

				case ';':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					if(openBraces > openBracesStartingS3) {
						// Correctly tokenise bogus properties containing {}'s, see CSS2.1 section 4.1.6.
						buffer.append(c);
						if(logDEBUG) log("openBraces now "+openBraces+" not moving on because openBracesStartingS3="+openBracesStartingS3+" in S3");
						break;
					}
					
					i = 0;
					for(i=0;i<buffer.length();i++) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					if(logDEBUG) log("Appending whitespace after colon: \""+buffer.substring(0,i)+"\"");
					whitespaceAfterColon = buffer.substring(0, i);
					propertyValue=buffer.delete(0, i).toString().trim();
					if(logDEBUG) log("Property value: "+propertyValue);
					buffer.setLength(0);

					ParsedWord[] words = split(propertyValue);
					if(logDEBUG) log("Split: "+CSSPropertyVerifier.toString(words));
					if(words != null && !ignoreElementsS2 && !ignoreElementsS3 && verifyToken(currentMedia,elements,propertyName,words))
					{
						if(changedAnything(words)) propertyValue = reconstruct(words);
						filteredTokens.append(whitespaceBeforeProperty);
						whitespaceBeforeProperty = "";
						filteredTokens.append(propertyName+":"+whitespaceAfterColon+propertyValue+";");
						if(logDEBUG) log("STATE3 CASE ;: appending "+ propertyName+":"+propertyValue);
						if(logDEBUG) log("filtered tokens now: \""+filteredTokens.toString()+"\"");
					} else {
						if(logDEBUG) log("filtered tokens now (ignored): \""+filteredTokens.toString()+"\" words="+CSSPropertyVerifier.toString(words)+" ignoreS1="+ignoreElementsS1+" ignoreS2="+ignoreElementsS2+" ignoreS3="+ignoreElementsS3);
					}
					ignoreElementsS3 = false;
					propertyName="";
					propertyValue="";
					break;
				case '}':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					openBraces--;
					if(openBraces > openBracesStartingS3-1) {
						// Correctly tokenise bogus properties containing {}'s, see CSS2.1 section 4.1.6.
						buffer.append(c);
						if(logDEBUG) log("openBraces now "+openBraces+" not moving on because openBracesStartingS3="+openBracesStartingS3+" in S3");
						if(openBraces < 0) openBraces = 0;
						break;
					}
					if(openBraces < 0) openBraces = 0;
					for(i=buffer.length()-1;i>=0;i--) {
						char c1 = buffer.charAt(i);
						if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
							continue;
						break;
					}
					i++;
					String postSpace = buffer.substring(i);
					buffer.setLength(i);
					// This (string!=) is okay as we set it directly by propertyName="" to indicate there is no property name.
					if(propertyName!="")
					{
						
						i = 0;
						for(i=0;i<buffer.length();i++) {
							char c1 = buffer.charAt(i);
							if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
								continue;
							break;
						}
						if(logDEBUG) log("Appending whitespace after colon (}): "+buffer.substring(0,i));
						whitespaceAfterColon = buffer.substring(0, i);
						buffer.delete(0, i);
						
						propertyValue=buffer.toString().trim();
						if(logDEBUG) log("Property value: "+propertyValue);
						buffer.setLength(0);

						if(logDEBUG) log("Found PropertyName:"+propertyName+" propertyValue:"+propertyValue);
						words = split(propertyValue);
						if(logDEBUG) log("Split: "+CSSPropertyVerifier.toString(words));
						if(!ignoreElementsS2 && !ignoreElementsS3 && verifyToken(currentMedia,elements,propertyName,words))
						{
							if(changedAnything(words)) propertyValue = reconstruct(words);
							filteredTokens.append(whitespaceBeforeProperty);
							whitespaceBeforeProperty = "";
							filteredTokens.append(propertyName+":"+whitespaceAfterColon+propertyValue);
							if(logDEBUG) log("STATE3 CASE }: appending "+ propertyName+":"+propertyValue);
						}
						propertyName="";
					} else {
						// Whitespace at end
						i = 0;
						for(i=0;i<buffer.length();i++) {
							char c1 = buffer.charAt(i);
							if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
								continue;
							break;
						}
						if(logDEBUG) log("Appending whitespace after colon (}): "+buffer.substring(0,i));
						filteredTokens.append(buffer.substring(0, i));
						buffer.delete(0, i);
						
					}
					ignoreElementsS3 = false;
					if((!ignoreElementsS2) || closeIgnoredS2) {
						filteredTokens.append(postSpace);
						filteredTokens.append("}");
						closeIgnoredS2 = false;
						ignoreElementsS2 = false;
					} else
						ignoreElementsS2=false;
					if(!ignoreElementsS1) {
						w.write(filteredTokens.toString());
						if(logDEBUG) log("writing filtered tokens: \""+filteredTokens.toString()+"\"");
					}
					filteredTokens.setLength(0);
					whitespaceAfterColon = "";
					if(forPage) {
						forPage = false;
						currentState = STATE1;
					} else {
						currentState=STATE2;
					}
					if(isInline) return;
					buffer.setLength(0);
					s2Comma=false;
					if(logDEBUG) log("STATE3 CASE }: "+c);
					break;

				case '{':
					// Correctly tokenise invalid properties including {}, see CSS2 section 4.1.6.
					openBraces++;
					buffer.append(c);
					if(logDEBUG) log("openBraces now "+openBraces+" in S3");
					break;
				case '"':
				case '\'':
					if(prevc == '\\') {
						// Leave in buffer, encoded.
						buffer.append(c);
						break;
					}
					buffer.append(c);
					currentState=STATE3INQUOTE;
					currentQuote=c;
					break;

				default:
					buffer.append(c);
				if(logDEBUG) log("STATE3 default CASE : "+c);
				break;

				}
				break;

			case STATE3INQUOTE:
				charsetPossible=false;
				if(stopAtDetectedCharset)
					return;
				if(logDEBUG) log("STATE3INQUOTE: "+c);
				switch(c)
				{
				case '"':
					if(currentQuote=='"'&& prevc!='\\')
						currentState=STATE3;
					buffer.append(c);
					break;
				case '\'':
					if(currentQuote=='\''&& prevc!='\\')
						currentState=STATE3;
					buffer.append(c);
					break;
				case '\n':
					if(prevc == '\r') {
						break;
					}
					// Otherwise same as \r ...
				case '\r':
				case '\f':
					if(prevc != '\\') {
						ignoreElementsS3 = true;
						currentState = STATE3;
						break;
					} else {
						// Wipe out the \ as well.
						buffer.setLength(buffer.length()-1);
						break;
					}
				default:
					buffer.append(c);
				break;
				}
				break;

			case STATECOMMENT:
				// FIXME sanitize (remove potentially dangerous chars) and preserve comments.
				charsetPossible=false;
				if(stopAtDetectedCharset)
					return;
				switch(c)
				{
				case '/':
					if(prevc=='*')
					{
						currentState=stateBeforeComment;
						if(logDEBUG) log("Exiting the comment state");
					}
					break;
				}
				break;


			}

		}
		
		if(logDEBUG) log("Filtered tokens: \""+filteredTokens+"\"");
		w.write(filteredTokens.toString());
		for(int i=0;i<openBraces;i++)
			w.write('}');

		if(logDEBUG) log("Remaining buffer: \""+buffer+"\"");
		
		int i = 0;
		for(i=0;i<buffer.length();i++) {
			char c1 = buffer.charAt(i);
			if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
				continue;
			break;
		}
		w.write(buffer.substring(0, i));
		buffer.delete(0, i);
		
		while(buffer.toString().trim().equals("-->")) {
			w.write("-->");
			buffer.delete(0, 3);
			for(i=0;i<buffer.length();i++) {
				char c1 = buffer.charAt(i);
				if(c1 == ' ' || c1 == '\f' || c1 == '\t' || c1 == '\r' || c1 == '\n')
					continue;
				break;
			}
			w.write(buffer.substring(0, i));
			buffer.delete(0, i);
		}

		// FIXME CSS2.1 section 4.2 "Unexpected end of style sheet".
		// We do NOT auto-close at the end.
		// It might be worth implementing this one day.
		
	}

	private String reconstruct(ParsedWord[] words) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for(ParsedWord word : words) {
			if(!first) sb.append(" ");
			if(!word.changed) {
				sb.append(word.original);
				if(logDEBUG) log("Adding word (original): \""+word.original+"\"");
			} else {
				sb.append(word.encode(false)); // FIXME check if charset is full unicode, if so pass true
				if(logDEBUG) log("Adding word (new): \""+word.encode(false)+"\"");
			}
			first = false;
		}
		if(logDEBUG) log("Reconstructed: \""+sb.toString()+"\"");
		return sb.toString();
	}


	private boolean changedAnything(ParsedWord[] words) {
		for(ParsedWord word : words) {
			if(word.changed) return true;
		}
		return false;
	}


	private ArrayList<String> commaListFromIdentifiers(ParsedWord[] strparts, int offset) {
		ArrayList<String> medias = new ArrayList<String>(strparts.length-1);
		if(strparts.length <= offset) {
			// Nothing to munch
		} else if(strparts.length == offset+1 && strparts[1] instanceof ParsedIdentifier) {
			medias.add(((ParsedIdentifier)strparts[1]).getDecoded());
		} else {
			boolean first = true;
			for(ParsedWord word : strparts) {
				if(first) {
					first = false;
					continue;
				}
				if(word instanceof ParsedIdentifier) {
					medias.add(((ParsedIdentifier)word).getDecoded());
				} else if(word instanceof SimpleParsedWord) {
					String data = ((SimpleParsedWord)word).original;
					String[] split = FilterUtils.removeWhiteSpace(data.split(","),false);
					for(String s : split) medias.add(s);
				} else return null;
			}
		}
		return medias;
	}

	static abstract class ParsedWord {
		
		final String original;
		/** Has decoded changed? If not we can use the original. */
		protected boolean changed;
		
		public ParsedWord(String original, boolean changed) {
			this.original = original;
			this.changed = changed;
		}

		public String encode(boolean unicode) {
			if(!changed)
				return original;
			else {
				StringBuffer out = new StringBuffer();
				innerEncode(unicode, out);
				return out.toString();
			}
		}
		
		public String toString() {
			return super.toString()+":\""+original+"\"";
		}
		
		abstract protected void innerEncode(boolean unicode, StringBuffer out);
		
	}
	
	/** Note that this does not represent functionThingy's. 
	 * counter() for example can contain a string, it is difficult to 
	 * determine whether to encode strings if we don't know they are strings!
	 * This only handles keywords and strings.
	 */
	static abstract class BaseParsedWord extends ParsedWord {
		private String decoded;
		
		/**
		 * @param original
		 * @param decoded
		 * @param changed Set this to true if we don't like the original
		 * encoding and have changed something during decode.
		 */
		BaseParsedWord(String original, String decoded, boolean changed) {
			super(original, changed);
			this.decoded = decoded;
		}
		
		protected void innerEncode(boolean unicode, StringBuffer out) {
			char prevc = 0;
			char c = 0;
			for(int i=0;i<decoded.length();i++) {
				prevc = c;
				c = decoded.charAt(i);
				if(!mustEncode(c, i, prevc, unicode)) {
					out.append(c);
				} else {
					encodeChar(c, out);
				}
			}
		}

		abstract protected boolean mustEncode(char c, int i, char prevc, boolean unicode);
		
		private void encodeChar(char c, StringBuffer sb) {
			String s = Integer.toHexString(c);
			sb.append('\\');
			if(s.length() == 6)
				sb.append(s);
			else if(s.length() > 6)
				throw new IllegalStateException();
			else {
				int x = 6 - s.length();
				for(int i=0;i<x;i++)
					sb.append('0');
				sb.append(s);
			}
		}
		
		public String getDecoded() {
			return decoded;
		}

		public void setNewValue(String s) {
			this.changed = true;
			this.decoded = s;
		}

	}
	
	static class ParsedIdentifier extends BaseParsedWord {
		
		ParsedIdentifier(String original, String decoded, boolean changed) {
			super(original, decoded, changed);
		}
		
		protected boolean mustEncode(char c, int i, char prevc, boolean unicode) {
			// It is an identifier.
			if((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
					(c >= '0' && c <= '9') || c == '-' || c == '_'
						|| (c >= (char)0x00A1 && unicode)) {
				// Cannot start with a digit or a hyphen followed by a digit.
				if(!((i == 0 && (c >= '0' && c <= '9')) ||
						(i == 1 && prevc == '-' &&
								(c >= '0' && c <= '9'))))
					return false;
			} 
			return true;
		}
		
	}
	
	static class ParsedString extends BaseParsedWord {
		
		ParsedString(String original, String decoded, boolean changed, char stringChar) {
			super(original, decoded, changed);
			this.stringChar = stringChar;
		}
		
		/** Is the word quoted? If true, the word is completely enclosed 
		 * by the given string character (either ' or "), which are not 
		 * included in the decoded string. */
		final char stringChar;
		
		protected boolean mustEncode(char c, int i, char prevc, boolean unicode) {
			// It is a string.
			// Anything is allowed in a string...
			if(c == '\r' || c == '\n' || c == '\f')
				// Except newlines.
				return true;
			else if(c == stringChar)
				// And the quote itself.
				return true;
			else if(c < 32 || (c >= (char)0x0080 && !unicode))
				// And control chars, and anything outside Basic Latin (unless we know the output charset is unicode-complete).
				return true;
			return false;
		}
		
		protected void innerEncode(boolean unicode, StringBuffer out) {
			out.append(stringChar);
			super.innerEncode(unicode, out);
			out.append(stringChar);
		}
	}
	
	static class ParsedURL extends ParsedString {
		
		ParsedURL(String original, String decoded, boolean changed, char stringChar) {
			super(original, decoded, changed || stringChar == 0, stringChar == 0 ? '"' : stringChar);
		}
		
		protected void innerEncode(boolean unicode, StringBuffer out) {
			out.append("url(");
			super.innerEncode(unicode, out);
			out.append(')');
		}

		public void setNewURL(String s) {
			super.setNewValue(s);
		}
		
	}
	
	static class ParsedAttr extends ParsedIdentifier {

		ParsedAttr(String original, String decoded, boolean changed) {
			super(original, decoded, changed);
		}
		
		protected void innerEncode(boolean unicode, StringBuffer out) {
			out.append("attr(");
			super.innerEncode(unicode, out);
			out.append(')');
		}
		
	}
	
	/** Simple parsed word, doesn't need encoding, won't be changed. Used 
	 * for lengths, percentages, angles, etc. All characters must be safe
	 * and non-problematic. This is used for everything from lengths and
	 * percentages to rgb(...) with spaces in it. Anything we don't 
	 * understand gets a SimpleParsedWord.
	 * */
	static class SimpleParsedWord extends ParsedWord {

		public SimpleParsedWord(String original) {
			super(original, false);
		}

		@Override
		protected void innerEncode(boolean unicode, StringBuffer out) {
			out.append(original);
		}
		
	}
	
	/** Counters need special handling, partly because they contain 
	 * attributes and strings. */
	static class ParsedCounter extends ParsedWord {
		public ParsedCounter(String original, ParsedIdentifier identifier, ParsedIdentifier listType, ParsedString separatorString) {
			super(original, true);
			this.identifier = identifier;
			this.listType = listType;
			this.separatorString = separatorString;
		}

		private final ParsedIdentifier identifier;
		private final ParsedIdentifier listType;
		private final ParsedString separatorString;
		
		@Override
		protected void innerEncode(boolean unicode, StringBuffer out) {
			if(separatorString != null)
				out.append("counters(");
			else
				out.append("counter(");
			identifier.innerEncode(unicode, out);
			if(separatorString != null) {
				out.append(", ");
				separatorString.innerEncode(unicode, out);
			}
			if(listType != null) {
				out.append(", ");
				listType.innerEncode(unicode, out);
			}
			out.append(')');
		}
		
	}
	
	/** Split up a string, taking into account CSS rules for escaping,
	 * strings, identifiers.
	 * @param str1
	 * @return
	 */
	private static ParsedWord[] split(String input) {
		if(logDEBUG) log("Splitting \""+input+"\"");
		ArrayList<ParsedWord> words = new ArrayList<ParsedWord>();
		char prevc = 0;
		char c = 0;
		// ", ' or 0 (not in string)
		char stringchar = 0;
		boolean escaping = false;
		/** Eat the next linefeed due to an escape closing. We turned the
		 * \r into a space, so we just ignore the \n. */
		boolean eatLF = false;
		/** The original token */
		StringBuffer origToken = new StringBuffer(input.length());
		/** The decoded token */
		StringBuffer decodedToken = new StringBuffer(input.length());
		/** We don't like the original token, it bends the spec in unacceptable ways */
		boolean dontLikeOrigToken = false;
		StringBuffer escape = new StringBuffer(6);
		boolean couldBeIdentifier = true;
		// Brackets prevent tokenisation, see e.g. rgb().
		int bracketCount = 0;
		for(int i=0;i<input.length();i++) {
			prevc = c;
			c = input.charAt(i);
			if(stringchar == 0) {
				if(eatLF && c == '\n') {
					eatLF = false;
					continue;
				} else
					eatLF = false;
				// Not in a string
				if(!escaping) {
					if(" \t\r\n\f".indexOf(c) != -1 && bracketCount == 0) {
						// Legal CSS whitespace
						if(decodedToken.length() > 0) {
							ParsedWord word = parseToken(origToken, decodedToken, dontLikeOrigToken, couldBeIdentifier);
							if(logDEBUG) log("Token: orig: \""+origToken.toString()+"\" decoded: \""+decodedToken.toString()+"\" dontLike="+dontLikeOrigToken+" couldBeIdentifier="+couldBeIdentifier+" parsed "+word);
							if(word == null) return null;
							words.add(word);
							origToken.setLength(0);
							decodedToken.setLength(0);
							dontLikeOrigToken = false;
							couldBeIdentifier = true;
						} // Else ignore.
					} else if(c == '\"') {
						stringchar = c;
						origToken.append(c);
						decodedToken.append(c);
						couldBeIdentifier = false;
					} else if(c == '\'') {
						stringchar = c;
						origToken.append(c);
						decodedToken.append(c);
						couldBeIdentifier = false;
					} else if(c == '\\') {
						origToken.append(c);
						escape.setLength(0);
						escaping = true;
					} else if(c == '(') {
						bracketCount++;
						origToken.append(c);
						decodedToken.append(c);
						couldBeIdentifier = false;
					} else if(c == ')') {
						bracketCount--;
						if(bracketCount < 0)
							return null;
						origToken.append(c);
						decodedToken.append(c);
						couldBeIdentifier = false;
					} else {
						if(couldBeIdentifier) {
							if(!((c >= '0' && c <= '9' && origToken.length() > 0) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_' || c >= 0xA1))
								couldBeIdentifier = false;
							if(origToken.length() == 1 && origToken.charAt(0) == '-' && (c >= '0' && c <= '9'))
								couldBeIdentifier = false;
						}
						origToken.append(c);
						decodedToken.append(c);
					}
				} else if(escaping && escape.length() == 0) {
					if(c == '\"' || c == '\'') {
						escaping = false;
						origToken.append(c);
						decodedToken.append(c);
					} else if((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
						escape.append(c);
					} else if(c == '\n' || c == '\r' || c == '\f') {
						// Newline. Can only be escaped in a string.
						// Not valid so return null.
						return null;
					} else {
						escaping = false;
						origToken.append(c);
						decodedToken.append(c);
					}
				} else /*if(escaping && escape.length() != 0)*/ {
					if((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
						escape.append(c);
						if(escape.length() == 6) {
							origToken.append(escape);
							decodedToken.append((char)Integer.parseInt(escape.toString(), 16));
							escape.setLength(0);
							escaping = false;
						}
					} else if(" \t\r\n\f".indexOf(c) != -1) {
						// Whitespace other than CR terminates the escape without any further significance.
						origToken.append(escape);
						decodedToken.append((char)Integer.parseInt(escape.toString(), 16));
						// Convert it to standard whitespace to avoid any complications.
						origToken.append(" ");
						escape.setLength(0);
						escaping = false;
						// \r terminates the escape but might be followed by a \n
						if(c == '\r')
							eatLF = true;
					} else {
						// Already started the escape, anything other than a hex digit or whitespace is invalid.
						return null;
					}
				}
			} else {
				// We are in a string.
				
				if(eatLF && c == '\n') {
					// Slightly different meaning here.
					// Here we do want to include it in the string.
					eatLF = false;
					origToken.append(c);
					// Don't add to decoded because it is invisible along with the preceding \
					continue;
				} else
					eatLF = false;
				
				if(c == stringchar && !escaping) {
					origToken.append(c);
					decodedToken.append(c);
					stringchar = 0;
				} else if(c == '\f' || c == '\r' || c == '\n' && !escaping) {
					// Invalid end of line in string.
					// The whole construct is invalid.
					// That is, usually everything up to the next semicolon.
					return null;
				} else if(c == '\\' && !escaping) {
					escaping = true;
					escape.setLength(0);
					origToken.append(c);
				} else if(escaping && escape.length() == 0) {
					if(c == '\"' || c == '\'') {
						escaping = false;
						origToken.append(c);
						decodedToken.append(c);
					} else if((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
						escape.append(c);
					} else if(c == '\r' || c == '\n' || c == '\f') {
						// In a string, an escaped newline is equal to nothing.
						origToken.append(c);
						// Do not add to decodedToken because both the \ and the \n are ignored.
						// Eat the \n if necessary (copy it to the origToken but not the decodedToken)
						if(c == '\r') eatLF = true;
					} else {
						origToken.append(c);
						decodedToken.append(c);
						// Escape one character.
						escaping = false;
					}
				} else if(escaping/* && escape.length() > 0*/) {
					if((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
						escape.append(c);
						if(escape.length() == 6) {
							origToken.append(escape);
							decodedToken.append((char)Integer.parseInt(escape.toString(), 16));
							escape.setLength(0);
							escaping = false;
						}
					} else if(" \t\r\n\f".indexOf(c) != -1) {
						// Whitespace other than CR terminates the escape without any further significance.
						origToken.append(escape);
						decodedToken.append((char)Integer.parseInt(escape.toString(), 16));
						escape.setLength(0);
						escaping = false;
						// \r terminates the escape but might be followed by a \n
						if(c == '\r') {
							eatLF = true;
							// Messy...
							dontLikeOrigToken = true;
						}
					} else {
						// Already started the escape, anything other than a hex digit or whitespace is invalid.
						return null;
					}
				} else { // escaping = false
					origToken.append(c);
					decodedToken.append(c);
				}
			}
			
		}
		if(escaping && escape.length() > 0) {
			origToken.append(escape);
			decodedToken.append((char)Integer.parseInt(escape.toString(), 16));
		} else if(escaping) {
			// Newline rule?
			dontLikeOrigToken = true;
		}
		if(origToken.length() > 0) {
			if(logDEBUG) log("Token: orig: \""+origToken.toString()+"\" decoded: \""+decodedToken.toString()+"\" dontLike="+dontLikeOrigToken+" couldBeIdentifier="+couldBeIdentifier);
			ParsedWord word = parseToken(origToken, decodedToken, dontLikeOrigToken, couldBeIdentifier);
			if(word == null) return null;
			words.add(word);
		}
		return words.toArray(new ParsedWord[words.size()]);
	}


	private static ParsedWord parseToken(StringBuffer origToken, StringBuffer decodedToken, boolean dontLikeOrigToken, boolean couldBeIdentifier) {
		if(origToken.length() > 2) {
			char c = origToken.charAt(0);
			if(c == '\'' || c == '\"') {
				char d = origToken.charAt(origToken.length()-1);
				if(c == d) {
					// The word is a string.
					decodedToken.setLength(decodedToken.length()-1);
					decodedToken.deleteCharAt(0);
					return new ParsedString(origToken.toString(), decodedToken.toString(), dontLikeOrigToken, c);
				} else {
					if(d != ',') {
						// No whitespace after a string...
						return null;
					} else return new SimpleParsedWord(origToken.toString());
				}
			}
		}
		
		String s = origToken.toString();
		if(couldBeIdentifier)
			return new ParsedIdentifier(s, decodedToken.toString(), dontLikeOrigToken);
		
		String sl = s.toLowerCase();
		if(sl.startsWith("url(")) {
			if(s.endsWith(")")) {
				decodedToken.delete(0, 4);
				decodedToken.setLength(decodedToken.length()-1);
				if(logDEBUG) log("stripped: "+decodedToken);
				
				// Trim whitespace from both ends
				
				String strippedOrig = s.substring(4, s.length()-1);
				int i;
				for(i=0;i<strippedOrig.length();i++) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
				}
				decodedToken.delete(0, i);
				strippedOrig = strippedOrig.substring(i);
				for(i=strippedOrig.length()-1;i>=0;i--) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
					if(i > 0 && strippedOrig.charAt(i-1) == '\\') break;
				}
				decodedToken.setLength(decodedToken.length()-(strippedOrig.length()-i-1));
				strippedOrig = strippedOrig.substring(0, i+1);
				
				if(logDEBUG) log("whitespace stripped: "+strippedOrig+" decoded "+decodedToken);
				
				if(strippedOrig.length() == 0) return null;
				
				if(strippedOrig.length() > 2) {
					char c = strippedOrig.charAt(0);
					if(c == '\'' || c == '\"') {
						char d = strippedOrig.charAt(strippedOrig.length()-1);
						if(c == d) {
							// The word is a string.
							decodedToken.setLength(decodedToken.length()-1);
							decodedToken.deleteCharAt(0);
							if(logDEBUG) log("creating url(): orig=\""+origToken.toString()+"\" decoded=\""+decodedToken.toString()+"\"");
							return new ParsedURL(origToken.toString(), decodedToken.toString(), dontLikeOrigToken, c);
						} else
							return null;
					}
				}
				return new ParsedURL(origToken.toString(), decodedToken.toString(), dontLikeOrigToken, (char)0);
			} else return null;
		}
		
		if(sl.startsWith("attr(")) {
			if(s.endsWith(")")) {
				decodedToken.delete(0, 5);
				decodedToken.setLength(decodedToken.length()-1);
				
				// Trim whitespace from both ends
				
				String strippedOrig = s.substring(4, s.length()-1);
				int i;
				for(i=0;i<strippedOrig.length();i++) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
				}
				decodedToken.delete(0, i);
				strippedOrig = strippedOrig.substring(i);
				for(i=strippedOrig.length()-1;i>=0;i--) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
					if(i > 0 && strippedOrig.charAt(i-1) == '\\') break;
				}
				decodedToken.setLength(decodedToken.length()-(strippedOrig.length()-i-1));
				strippedOrig = strippedOrig.substring(0, i+1);
				
				if(strippedOrig.length() == 0) return null;
				
				return new ParsedAttr(origToken.toString(), decodedToken.toString(), dontLikeOrigToken);
			} else return null;
		}

		boolean plural = false;
		if(sl.startsWith("counter(") || (plural = sl.startsWith("counters("))) {
			if(s.endsWith(")")) {
				int len = plural ? "counters(".length() : "counter(".length();
				decodedToken.delete(0, len);
				decodedToken.setLength(decodedToken.length()-1);
				
				// Trim whitespace from both ends
				
				String strippedOrig = s.substring(len, s.length()-1);
				int i;
				for(i=0;i<strippedOrig.length();i++) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
				}
				decodedToken.delete(0, i);
				strippedOrig = strippedOrig.substring(i);
				for(i=strippedOrig.length()-1;i>=0;i--) {
					char c = strippedOrig.charAt(i);
					if(!(c == ' ' || c == '\t')) break;
					if(i > 0 && strippedOrig.charAt(i-1) == '\\') break;
				}
				decodedToken.setLength(decodedToken.length()-(strippedOrig.length()-i-1));
				strippedOrig = strippedOrig.substring(0, i+1);
				
				if(strippedOrig.length() == 0) return null;
				
				String[] split = FilterUtils.removeWhiteSpace(strippedOrig.split(","),false);
				if(split.length == 0 || (plural && split.length > 3) || ((!plural) && split.length > 2) || (plural && split.length < 2))
					return null;
				
				ParsedIdentifier ident = makeParsedIdentifier(split[0]);
				if(ident == null) return null;
				ParsedString separator = null;
				ParsedIdentifier listType = null;
				if(plural) {
					separator = makeParsedString(split[1]);
					if(separator == null) return null;
				}
				if(((!plural) && split.length == 2) || (plural && split.length == 3)) {
					listType = makeParsedIdentifier(split[split.length-1]);
					if(listType == null) return null;
				}
				return new ParsedCounter(origToken.toString(), ident, listType, separator);
			} else return null;
		}
		
		return new SimpleParsedWord(origToken.toString());
	}

	private static ParsedIdentifier makeParsedIdentifier(String string) {
		ParsedWord[] words = split(string);
		if(words == null) return null;
		if(words.length != 1) return null;
		if(!(words[0] instanceof ParsedIdentifier)) return null;
		return (ParsedIdentifier)words[0];
	}

	private static ParsedString makeParsedString(String string) {
		ParsedWord[] words = split(string);
		if(words == null) return null;
		if(words.length != 1) return null;
		if(!(words[0] instanceof ParsedString)) return null;
		return (ParsedString)words[0];
	}

	/*
	 * Basic class to verify value for a CSS Property. This class can verify values which are
	 * Integer,Real,Percentage, <Length>, <Angle>, <Color>, <URI>, <Shape> and so on.
	 * parserExpression is used for verifying regular expression for Property value
	 * e.g. [ <color> | transparent]{1,4}.
	 */
	static class CSSPropertyVerifier
	{
		public HashSet<String> allowedElements=null; // HashSet for all valid HTML elements which can have this CSS property
		public HashSet<String> allowedValues=null; //HashSet for all String constants that this CSS property can assume like "inherit"
		public HashSet<String> allowedMedia=null; // HashSet for all valid Media for this CSS property.
		/*
		 * in, re etc stands for different code strings using which these boolean values can be set in
		 * constructor like passing in,re would set isInteger and isReal.
		 */
		public boolean isInteger=false; //in
		public boolean isReal=false;	//re
		public boolean isPercentage=false;	//pe
		public boolean isLength=false;	//le
		public boolean isAngle=false;	//an	
		public boolean isColor=false; //co	
		public boolean isURI=false;	//ur
		public boolean isShape=false;	//sh	
		public boolean isString=false;//st
		public boolean isCounter=false; //co
		public boolean isIdentifier=false; //id
		public boolean isTime=false; //ti
		public boolean isFrequency=false; //fr
		public boolean onlyValueVerifier=false;
		public String[] cssPropertyList=null; 
		public String[] parserExpressions=null;
		CSSPropertyVerifier()
		{}


		CSSPropertyVerifier(String[] allowedElements,String[] allowedValues,String[] allowedMedia)
		{
			this(allowedElements,allowedValues,allowedMedia,null,null);
		}

		CSSPropertyVerifier(String[] allowedValues,String[] possibleValues,String[] expression,boolean onlyValueVerifier) {
			this(null,allowedValues,null,possibleValues,expression);
			this.onlyValueVerifier=onlyValueVerifier;
		}

		CSSPropertyVerifier(String[] allowedValues,String[] possibleValues,String[] expression,String[] media, boolean onlyValueVerifier)
		{
			this(null,allowedValues,media,possibleValues,expression);
			this.onlyValueVerifier=onlyValueVerifier;

		}

		CSSPropertyVerifier(String[] allowedElements,String[] allowedValues,String[] allowedMedia,String[] possibleValues,String[] parseExpression)
		{
			if(possibleValues!=null)
			{
				for(String possibleValue:possibleValues)
				{
					if("in".equals(possibleValue))
						isInteger=true; //in
					else if("re".equals(possibleValue))
						isReal=true;	//re
					else if("pe".equals(possibleValue))
						isPercentage=true;	//pe
					else if("le".equals(possibleValue))
						isLength=true;	//le
					else if("an".equals(possibleValue))
						isAngle=true;	//an
					else if("co".equals(possibleValue))
						isColor=true; //co
					else if("ur".equals(possibleValue))
						isURI=true;	//ur
					else if("sh".equals(possibleValue))
						isShape=true;	//sh
					else if("st".equals(possibleValue))
						isString=true;//st
					else if("co".equals(possibleValue))
						isCounter=true; //co
					else if("id".equals(possibleValue))
						isIdentifier=true; //id
					else if("ti".equals(possibleValue))
						isTime=true; //ti
					else if("fr".equals(possibleValue))
						isFrequency=true;
				}
			}
			if(allowedElements!=null)
			{
				this.allowedElements= new HashSet<String>();
				for(int i=0;i<allowedElements.length;i++)
					this.allowedElements.add(allowedElements[i]);
			}

			if(allowedValues!=null)
			{
				this.allowedValues=new HashSet<String>();
				for(int i=0;i<allowedValues.length;i++)
					this.allowedValues.add(allowedValues[i]);
			}

			if(allowedMedia!=null)
			{
				this.allowedMedia=new HashSet<String>();
				for(int i=0;i<allowedMedia.length;i++)
					this.allowedMedia.add(allowedMedia[i]);
			}
			if(parseExpression!=null)
				this.parserExpressions=parseExpression.clone();
			else
				this.parserExpressions=null;

		}


		CSSPropertyVerifier(String[] allowedElements,String[] allowedValues,String[] allowedMedia,String[] possibleValues)
		{

			this(allowedElements,allowedValues,allowedMedia,possibleValues,null);
		}





		public static boolean isIntegerChecker(String value)
		{
			try{
				Integer.parseInt(value); //CSS Property has a valid integer.
				return true;
			}
			catch(Exception e) {return false; }

		}


		public static boolean isRealChecker(String value)
		{
			try
			{
				Float.parseFloat(value); //Valid float
				return true;
			}
			catch(Exception e){return false; }
		}
		public void log(String s)
		{
			Logger.debug(this,"CSSPropertyVerifier "+s);
			//System.out.println("CSSPropertyVerifier "+s);
			//log.println(s);
		}

		public static boolean isValidURI(ParsedURL word, FilterCallback cb)
		{
			String w = CSSTokenizerFilter.removeOuterQuotes(word.getDecoded());
			//if(debug) log("CSSPropertyVerifier isVaildURI called cb="+cb);
			try
			{
				//if(debug) log("CSSPropertyVerifier isVaildURI "+cb.processURI(URI, null));
				String s = cb.processURI(w, null);
				if(s == null || s.equals("")) return false;
				if(s.equals(w)) return true;
				if(logDEBUG) Logger.minor(CSSTokenizerFilter.class, "New url: \""+s+"\" from \""+w+"\"");
				word.setNewURL(s);
				return true;
			}
			catch(CommentException e)
			{
				//if(debug) log("CSSPropertyVerifier isVaildURI Exception"+e.toString());
				return false;
			}

		}

		public boolean checkValidity(ParsedWord[] words, FilterCallback cb)
		{
			return this.checkValidity(null,null, words, cb);
		}
		
		public boolean checkValidity(ParsedWord word, FilterCallback cb)
		{
			return this.checkValidity(null,null, new ParsedWord[] { word }, cb);
		}
		
		// Verifies whether this CSS property can have a value under given media and HTML elements
		public boolean checkValidity(String[] media,String[] elements,ParsedWord[] words, FilterCallback cb)
		{

			if(logDEBUG) log("checkValidity for "+toString(words)+" for "+this);
			if(!onlyValueVerifier)
			{
				if(allowedMedia!=null) {
					boolean allowed = false;
					for(String m : media)
						if(allowedMedia.contains(m)) {
							allowed = true;
							break;
						}
					if(!allowed) {
						if(logDEBUG) log("checkValidity Media of the element is not allowed.Media="+media+" allowed Media="+allowedMedia.toString());
						
						return false;
					}
				}
				if(elements!=null)
				{
					if(allowedElements!=null)
					{
						for(String element:elements)
						{

							if(!allowedElements.contains(element.trim().toLowerCase()))
							{
								if(logDEBUG) log("checkValidity: element is not allowed:"+element);
								return false;
							}
						}
					}
				}
			}

			if(words.length == 1) {

			if(words[0] instanceof ParsedIdentifier && allowedValues != null && allowedValues.contains(((ParsedIdentifier)words[0]).original.toLowerCase()))
			//CSS Property has one of the explicitly defined values
				return true;

			if(words[0] instanceof SimpleParsedWord) {
				
				String word = ((SimpleParsedWord)words[0]).original;
				
				// Numeric explicitly defined value is possible
				if(allowedValues != null && allowedValues.contains(word))
					return true;
				
				// These are all numeric so they will have parsed as a SimpleParsedWord.
				
				if(isInteger && isIntegerChecker(word))
				{
					return true;
				}
				
				if(isReal && isRealChecker(word))
				{
					return true;
				}
				
				if(isPercentage && FilterUtils.isPercentage(word)) //Valid percentage X%
				{
					return true;
				}
				
				if(isLength && FilterUtils.isLength(word,false)) //Valid unit Vxx where xx is unit or V
				{
					return true;
				}
				
				if(isAngle && FilterUtils.isAngle(word))
				{
					return true;
				}
				// This is not numeric but will still have parsed as a SimpleParsedWord, as it either starts with a # or has brackets in.
				if(isColor)
				{
					if(FilterUtils.isColor(word, false))
						return true;
				}
				
				if(isShape)
				{
					if(FilterUtils.isValidCSSShape(word))
						return true;
				}

				if(isFrequency)
				{
					if(FilterUtils.isFrequency(word))
						return true;
				}

			}
			
			if(words[0] instanceof ParsedIdentifier && isColor) {
				if(FilterUtils.isColor(((ParsedIdentifier)words[0]).original, false))
					return true;
				
			}			
			if(isURI && words[0] instanceof ParsedURL)
			
			{
				return isValidURI((ParsedURL)words[0], cb);
			}

			if(isIdentifier && words[0] instanceof ParsedIdentifier)
			{
				return true;
			}

			if(isString && words[0] instanceof ParsedString)
			{
				if(ElementInfo.ALLOW_ALL_VALID_STRINGS || ElementInfo.isValidStringDecoded(((ParsedString)words[0]).getDecoded()))
					return true;
				else
					return false;
			}
			
			}

			/*
			 * Parser expressions
			 * 1 || 2 => 1a2
			 * [1][2] =>1 2
			 * 1<1,4> => 1<1,4>
			 * 1* => 1<0,65536>
			 * 1? => 1o
			 * 
			 */  
			/*
			 * For each parserExpression, recursiveParserExpressionVerifier() would be called with parserExpression and value.
			 */
			if(parserExpressions!=null)
			{

				for(String parserExpression:parserExpressions)
				{
					boolean result=recursiveParserExpressionVerifier(parserExpression,words,cb);

					if(result)
						return true;
				}
			}

			return false;
		}
		/* parserExpression string would be interpreted as 1 || 2 => 1a2 here 1a2 would be written to parse 1 || 2 where 1 and 2 are auxilaryVerifiers[1] and auxilaryVerifiers[2] respectively i.e. indices in auxilaryVerifiers
		 * [1][2] =>1b2
		 * 1<1,4> => 1<1,4>
		 * 1* => 1<0,65536>
		 * 1? => 1o
		 * 1+=>1<1,65536>
		 * Additional expressions that can be passed to the function
		 * 1 2 => both 1 and 2 should return true where 1 and 2 are again indices in auxiliaryVerifier array.
		 * [a,b]=> give at least a tokens and at the most b tokens(part of values) to this block of expression.
		 * e.g. 1[2,3] and the value is "hello world program" then object 1 would be tested with "hello world"
		 * and "hello world program".
		 * The main logic of this function is find a set of values for different part of the ParserExpression so that each part returns true.
		 * e.g. Suppose the expression is
		 * (1 || 2) 3
		 * where object 1 can consume upto 2 tokens, 2 can consume upto 2 tokens and 3 would consume one and only one token.
		 * This expression would be encoded as
		 * "1a2" for 1 || 2 Using this, third object would be created say 4 e.g. 4="1a2"
		 * Now the main object would be given the parserExpression as
		 * "4<0,4> 3"
		 * This function would call
		 * 4 with 0 tokens and 3 with the remaining
		 * 4 with 1 tokens and 3 with the remaining
		 * and so on.
		 * If all combinations are failed then it would return false. If any combination gives true value
		 * then return value would be true.
		 */
		public boolean recursiveParserExpressionVerifier(String expression,ParsedWord[] words, FilterCallback cb)
		{
			if(logDEBUG) log("1recursiveParserExpressionVerifier called: with "+expression+" "+toString(words));
			if((expression==null || ("".equals(expression.trim()))))
			{
				if(words==null || words.length == 0)
					return true;
				else 
					return false;
			}
			
			int tokensCanBeGivenLowerLimit=1,tokensCanBeGivenUpperLimit=1;
			for(int i=0;i<expression.length();i++)
			{
				if(expression.charAt(i)=='a') //Identifying ||
				{
					int noOfa=0;
					int endIndex=expression.length();
					//Detecting the other end
					for(int j=0;j<expression.length();j++)
					{
						if(expression.charAt(j)=='?' || expression.charAt(j)=='<' || expression.charAt(j)=='>' || expression.charAt(j)==' ')
						{
							endIndex=j;
							break;
						}
						else if(expression.charAt(j)=='a')
							noOfa++;

					}
					String firstPart=expression.substring(0,endIndex);
					String secondPart="";
					if(endIndex!=expression.length())
						secondPart=expression.substring(endIndex+1,expression.length());
					for(int j=1;j<=noOfa+1 && j<=words.length;j++)
					{
						if(logDEBUG) log("2Making recursiveDoubleBarVerifier to consume "+j+" words");
						ParsedWord[] partToPassToDB = new ParsedWord[j];
						System.arraycopy(words, 0, partToPassToDB, 0, j);
						if(logDEBUG) log("3Calling recursiveDoubleBarVerifier with "+firstPart+" "+partToPassToDB.toString());
						if(recursiveDoubleBarVerifier(firstPart,partToPassToDB,cb)) //This function is written to verify || operator.
						{
							ParsedWord[] partToPass = new ParsedWord[words.length-j];
							System.arraycopy(words, j, partToPass, 0, words.length-j);
							if(logDEBUG) log("4recursiveDoubleBarVerifier true calling itself with "+secondPart+partToPass.toString());
							if(recursiveParserExpressionVerifier(secondPart,partToPass,cb))
								return true;
						}
						if(logDEBUG) log("5Back to recursiveDoubleBarVerifier "+j+" "+(noOfa+1)+" "+words.length);
					}
					return false;
				}
				else if(expression.charAt(i)==' ')
				{
					String firstPart=expression.substring(0,i);
					String secondPart=expression.substring(i+1,expression.length());
					if(words!=null && words.length>0)
					{
						int index=Integer.parseInt(firstPart);
						boolean result=CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(words[0], cb);
						if(result)
						{
							ParsedWord[] partToPass = new ParsedWord[words.length-1];
							System.arraycopy(words, 1, partToPass, 0, words.length-1);
							if(logDEBUG) log("8First part is true. partToPass="+partToPass.toString());
							if(recursiveParserExpressionVerifier(secondPart,partToPass, cb))
								return true;
						}
					}
					return false;
				}
				else if(expression.charAt(i)=='?')
				{
					String firstPart=expression.substring(0,i);
					String secondPart=expression.substring(i+1,expression.length());
					int index=Integer.parseInt(firstPart);
					if(words.length>0)
					{
						boolean result= CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(words[0], cb);
						if(result)
						{
							ParsedWord[] partToPass = new ParsedWord[words.length-1];
							System.arraycopy(words, 1, partToPass, 0, words.length-1);
							if(recursiveParserExpressionVerifier(secondPart,partToPass, cb))
								return true;
						}
					}
					else if(recursiveParserExpressionVerifier(secondPart,words, cb))
						return true;

					return false;
				}
				else if(expression.charAt(i)=='<')
				{
					int tindex=expression.indexOf('>');
					if(tindex>i)
					{
						int firstIndex=tindex+1;
						if((tindex!=expression.length()-1) && expression.charAt(tindex+1)=='[')
						{
							int indexOfSecondBracket=expression.indexOf(']');
							if(indexOfSecondBracket>(tindex+1))
							{
								tokensCanBeGivenLowerLimit=Integer.parseInt(expression.substring(tindex+2,indexOfSecondBracket).split(",")[0]);
								tokensCanBeGivenUpperLimit=Integer.parseInt(expression.substring(tindex+2,indexOfSecondBracket).split(",")[1]);
								firstIndex=expression.indexOf(']')+1;
							}
						}
						String firstPart=expression.substring(0,i);
						String secondPart=expression.substring(firstIndex,expression.length());
						if(secondPart.length() > 0 && secondPart.charAt(0) == ' ') {
							secondPart = secondPart.substring(1);
						} else if(secondPart.length() > 0) {
							throw new IllegalStateException("Don't know what to do with char after <>[]: "+secondPart.charAt(0));
						}
						if(logDEBUG) log("9in < firstPart="+firstPart+" secondPart="+secondPart+" tokensCanBeGivenLowerLimit="+tokensCanBeGivenLowerLimit+" tokensCanBeGivenUpperLimit="+tokensCanBeGivenUpperLimit);
						int index=Integer.parseInt(firstPart);
						String[] strLimits=expression.substring(i+1,tindex).split(",");
						if(strLimits.length==2)
						{
							int lowerLimit=Integer.parseInt(strLimits[0]);
							int upperLimit=Integer.parseInt(strLimits[1]);
							
							if(recursiveVariableOccuranceVerifier(index, words, lowerLimit,upperLimit,tokensCanBeGivenLowerLimit,tokensCanBeGivenUpperLimit, secondPart, cb))
								return true;
						}
					}

					return false;
				}

			}
			//Single verifier object
			if(logDEBUG) log("10Single token:"+expression);
			int index=Integer.parseInt(expression);
			return CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(words, cb);


		}
		/*
		 * This function takes an array of string and concatenates everything in a " " seperated string.
		 */
		public static String getStringFromArray(String[] parts,int lowerIndex,int upperIndex)
		{
			StringBuffer buffer=new StringBuffer();
			if(parts!=null && lowerIndex<parts.length)
			{
				for(int i=lowerIndex;i<upperIndex && i<parts.length;i++)
					buffer.append(parts[i]+ " ");
				return buffer.toString();
			}
			else
				return "";

		}
		/*
		 * This function takes an array of string and concatenates everything in a " " seperated string.
		 */
		public static String getStringFromArray(String[] parts) {
			return getStringFromArray(parts, 0, parts.length-1);
		}
		//Creates a new sub array from the main array and returns it.
		public static ParsedWord[] getSubArray(ParsedWord[] array,int lowerIndex,int upperIndex)
		{
			ParsedWord[] arrayToReturn=new ParsedWord[upperIndex-lowerIndex];
			if(array!=null && lowerIndex<array.length)
			{
				for(int i=lowerIndex;i<upperIndex && i<array.length;i++)
				{	
					arrayToReturn[i-lowerIndex]=array[i];
				}
				return arrayToReturn;
			}
			else
				return new ParsedWord[0];
		}
		/*
		 * For verifying part of the ParseExpression with [] operator.
		 */
		public boolean recursiveVariableOccuranceVerifier(int verifierIndex,ParsedWord[] valueParts,int lowerLimit,int upperLimit,int tokensCanBeGivenLowerLimit,int tokensCanBeGivenUpperLimit, String secondPart, FilterCallback cb)
		{

			if(logDEBUG) log("recursiveVariableOccurranceVerifier("+verifierIndex+","+toString(valueParts)+","+lowerLimit+","+upperLimit+","+tokensCanBeGivenLowerLimit+","+tokensCanBeGivenUpperLimit+","+secondPart+")");
			if((valueParts==null || valueParts.length==0) && lowerLimit == 0)
				return true;
			
			if(lowerLimit <= 0) {
				// There could be secondPart.
				if(recursiveParserExpressionVerifier(secondPart, valueParts, cb)) {
					if(logDEBUG) log("recursiveVariableOccurranceVerifier completed by "+secondPart);
					return true;
				}
			}
			
			// There can be no more parts.
			if(upperLimit == 0) {
				if(logDEBUG) log("recursiveVariableOccurranceVerifier: no more parts");
				return false;
			}
			
			for(int i=tokensCanBeGivenLowerLimit; i<=tokensCanBeGivenUpperLimit && i <= valueParts.length;i++) {
				ParsedWord[] before = new ParsedWord[i];
				System.arraycopy(valueParts, 0, before, 0, i);
				if(CSSTokenizerFilter.auxilaryVerifiers[verifierIndex].checkValidity(before, cb)) {
					if(logDEBUG) log("first "+i+" tokens using "+verifierIndex+" match "+toString(before));
					if(i == valueParts.length && lowerLimit <= 1) {
						if(recursiveParserExpressionVerifier(secondPart, new ParsedWord[0], cb)) {
							if(logDEBUG) log("recursiveVariableOccurranceVerifier completed with no more parts by "+secondPart);
							return true;
						} else {
							if(logDEBUG) log("recursiveVariableOccurranceVerifier: satisfied self but nothing left to match "+secondPart);
							return false;
						}
					} else if(i == valueParts.length && lowerLimit > 1)
						return false;
					ParsedWord[] after = new ParsedWord[valueParts.length-i];
					System.arraycopy(valueParts, i, after, 0, valueParts.length-i);
					if(logDEBUG) log("rest of tokens: "+toString(after));
					if(recursiveVariableOccuranceVerifier(verifierIndex, after, lowerLimit-1, upperLimit-1, tokensCanBeGivenLowerLimit, tokensCanBeGivenUpperLimit, secondPart, cb))
						return true;
				}
			}
			
			return false;
		}

		static String toString(ParsedWord[] words) {
			if(words == null) return null;
			StringBuffer sb = new StringBuffer();
			boolean first = true;
			for(ParsedWord word : words) {
				if(!first) sb.append(",");
				first = false;
				sb.append(word);
			}
			return sb.toString();
		}

		/*
		 * This function verifies || operator. This function returns true only if it can consume entire value for the given parseExpression
		 * e.g. expression is [1 || 2 || 3 || 4] and value is "Hello world program"
		 * Then this function would try all combinations of objects so that the expression can consume this value.
		 * 1 would try to consume "Hello" and rest would try to consume "world program"
		 * 2 would try to consume "Hello" and rest would try to consume "world program"
		 * 3 would try to consume "Hello" and rest would try to consume "world program"
		 * and so on.
		 */
		public boolean recursiveDoubleBarVerifier(String expression,ParsedWord[] words,FilterCallback cb)
		{
			if(logDEBUG) log("11in recursiveDoubleBarVerifier expression="+expression+" value="+toString(words));
			if(words==null || words.length == 0)
				return true;

			String ignoredParts="";
			String firstPart = "";
			String secondPart = "";
			int lastA = -1;
			for(int i=0;i<=expression.length();i++)
			{
				if(i == expression.length() || expression.charAt(i)=='a')
				{
					if(!firstPart.equals("")) {
						if(ignoredParts.length() == 0)
							ignoredParts = firstPart;
						else
							ignoredParts = ignoredParts+"a"+firstPart;
					}
					else ignoredParts = "";
					firstPart=expression.substring(lastA+1,i);
					lastA = i;
					if(i == expression.length())
						secondPart = "";
					else
						secondPart=expression.substring(i+1,expression.length());
					if(logDEBUG) log("12in a firstPart="+firstPart+" secondPart="+secondPart+" for expression "+expression+" i "+i);

					boolean result=false;

					int index=Integer.parseInt(firstPart);
					for(int j=0;j<words.length;j++)
					{
						result=CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(getSubArray(words, 0, j+1), cb);
						if(logDEBUG) log("14in for loop result:"+result+" for "+toString(words)+" for "+firstPart);
						if(result)
						{
							ParsedWord[] valueToPass = new ParsedWord[words.length-j-1];
							System.arraycopy(words, j+1, valueToPass, 0, words.length-j-1);
							String pattern = ignoredParts+((ignoredParts.isEmpty()||secondPart.isEmpty())?"":"a")+secondPart;
							if(logDEBUG) log("14a "+toString(getSubArray(words, 0, j+1))+" can be consumed by "+index+ " passing on expression="+pattern+ " value="+toString(valueToPass));
							if(valueToPass.length == 0)
								return true;
							if(pattern.isEmpty()) return false;
							result=recursiveDoubleBarVerifier(pattern,valueToPass, cb);
							if(result)
							{
								if(logDEBUG) log("15else part is true, value consumed="+words[j]);
								return true;
							}
						}
					}
				}
			}
			
			if(lastA != -1) return false;
			//Single token
			int index=Integer.parseInt(expression);
			if(logDEBUG) log("16Single token:"+expression+" with value=*"+words+"* validity="+CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(words,cb));
			return CSSTokenizerFilter.auxilaryVerifiers[index].checkValidity(words,cb);


		}





	}
	//CSSPropertyVerifier class extended for verifying content property.
	static class contentPropertyVerifier extends CSSPropertyVerifier
	{

		contentPropertyVerifier(String[] allowedValues)
		{
			super(null,allowedValues,null,null,null);
		}

		@Override
		public boolean checkValidity(String[] media,String[] elements,ParsedWord[] value,FilterCallback cb)
		{
			if(logDEBUG) log("contentPropertyVerifier checkValidity called: "+toString(value));

			if(value.length != 1) return false;
			
			if(value[0] instanceof ParsedIdentifier && allowedValues!=null && allowedValues.contains(((ParsedIdentifier)value[0]).getDecoded()))
				return true;

			//String processing
			if(value[0] instanceof ParsedString) {
				if(ElementInfo.ALLOW_ALL_VALID_STRINGS || ElementInfo.isValidStringDecoded(((ParsedString)value[0]).getDecoded()))
					return true;
				else
					return false;
			}

			if(value[0] instanceof ParsedCounter) {
				ParsedCounter counter = (ParsedCounter)value[0];
				if(counter.listType != null) {
					HashSet<String> listStyleType=new HashSet<String>();
					listStyleType.add("disc");
					listStyleType.add("circle");
					listStyleType.add("square");
					listStyleType.add("decimal");
					listStyleType.add("decimal-leading-zero");
					listStyleType.add("lower-roman");
					listStyleType.add("upper-roman");
					listStyleType.add("lower-greek");
					listStyleType.add("lower-latin");
					listStyleType.add("upper-latin");
					listStyleType.add("armenian");
					listStyleType.add("georgian");
					listStyleType.add("lower-alpha");
					listStyleType.add("upper-alpha");
					listStyleType.add("none");
					if(!listStyleType.contains(counter.listType.getDecoded())) return false;
				}
				if(counter.separatorString != null && !(ElementInfo.ALLOW_ALL_VALID_STRINGS || ElementInfo.isValidStringDecoded(counter.separatorString.getDecoded())))
					return false;
				return true;
			}
			
			if(value[0] instanceof ParsedAttr) {
				ParsedAttr attr = (ParsedAttr) value[0];
				return true;
			}
			
			if(value[0] instanceof ParsedURL) {
				// CONFORMANCE: This is required by the spec, and quite useful in practice.
				// Browsers in practice only support images here.
				// However, as long as they respect the MIME type - and if they don't we are screwed anyway - this should be safe even if it allows including text, CSS and HTML.
				// Note also that generated content cannot alter the parse tree, so what can be done is presumably severely limited.
				return isValidURI((ParsedURL)value[0], cb);
			}
			
			return false;

		}



	}

	//For verifying ’font-size’[ / ’line-height’]? of Font property

	static class FontPartPropertyVerifier extends CSSPropertyVerifier
	{
		@Override
		public boolean checkValidity(String[] media,String[] elements,ParsedWord[] value,FilterCallback cb)
		{

			if(logDEBUG) log("FontPartPropertyVerifier called with "+toString(value));
			CSSPropertyVerifier fontSize=new CSSPropertyVerifier(new String[] {"xx-small","x-small","small","medium","large","x-large","xx-large","larger","smaller","inherit"},new String[]{"le","pe"},null,true);
			if(fontSize.checkValidity(value, cb)) return true;
			
			for(ParsedWord word : value) {
				// Token by token
				if(fontSize.checkValidity(word, cb)) continue;
				if(word instanceof SimpleParsedWord) {
					String orig = ((SimpleParsedWord)word).original;
					if(orig.indexOf("/")!=-1)
					{
						int slashIndex=orig.indexOf("/");
						String firstPart=orig.substring(0,slashIndex);
						String secondPart=orig.substring(slashIndex+1,orig.length());
						if(logDEBUG) log("FontPartPropertyVerifier FirstPart="+firstPart+" secondPart="+secondPart);
						CSSPropertyVerifier lineHeight=new CSSPropertyVerifier(new String[] {"normal","inherit"},new String[]{"le","pe","re","in"},null,true);
						ParsedWord[] first = split(firstPart);
						ParsedWord[] second = split(secondPart);
						if(first.length == 1 && second.length == 1 &&
								fontSize.checkValidity(first, cb) && lineHeight.checkValidity(second,cb))
							continue;
					}
				}
				return false;
			
			}
			return true;

		}
		
	}

	static class FontPropertyVerifier extends CSSPropertyVerifier {
		
		FontPropertyVerifier(boolean valueOnly) {
			super(null, null, null, ElementInfo.VISUALMEDIA, valueOnly);
		}
		
		// FIXME: We do not change the tokens.
		// We probably should put in quotes around unquoted font family names, put spaces after commas etc, but
		// this may be a bit tricky: we'd have to put spaces etc inside some words, and delete some words...
		// Quite possible, but not a high priority, "verdana,arial,times new roman,sans-serif" is not dangerous, it's just hard to parse.
		
		@Override
		public boolean checkValidity(String[] media,String[] elements,ParsedWord[] value,FilterCallback cb)
		{
			if(logDEBUG) log("font verifier: "+toString(value));
			if(value.length == 1) {
				if(value[0] instanceof ParsedIdentifier && "inherit".equals(((ParsedIdentifier)value[0]).getDecoded())) {
				//CSS Property has one of the explicitly defined values
					if(logDEBUG) log("font: inherit");
					return true;
				}
			}			
			if(allowedMedia!=null && !onlyValueVerifier) {
				boolean allowed = false;
				for(String m : media)
					if(allowedMedia.contains(m)) {
						allowed = true;
						break;
					}
				if(!allowed) {
					if(logDEBUG) log("checkValidity Media of the element is not allowed.Media="+media+" allowed Media="+allowedMedia.toString());
					
					return false;
				}
			}
			boolean hadFont = false;
			ArrayList<String> fontWords = new ArrayList<String>();
			// FIXME delete fonts we don't know about but let through ones we do.
			// Or allow unknown fonts given [a-z][A-Z][0-9] ???
outer:		for(int i=0;i<value.length;i++) {
				ParsedWord word = value[i];
				String s = null;
				if(word instanceof ParsedString) {
					String decoded = (((ParsedString)word).getDecoded());
					if(logDEBUG) log("decoded: \""+decoded+"\"");
					// It's actually quoted, great.
					if(ElementInfo.isSpecificFontFamily(decoded.toLowerCase())) {
						hadFont = true;
						continue;
					} if(ElementInfo.isGenericFontFamily(decoded.toLowerCase())) {
						hadFont = true;
						continue;
					} else
						s = decoded;
				} else if(word instanceof ParsedIdentifier) {
					s = (((ParsedIdentifier)word).getDecoded());
					if(ElementInfo.isGenericFontFamily(s)) {
						hadFont = true;
						continue;
					}
				} else if(word instanceof SimpleParsedWord) {
					s = ((SimpleParsedWord)word).original;
					s = s.trim();
					if(s.equals(",")) {
						if(hadFont) continue; // OK, there was a previous font.
						if(logDEBUG) log("out of context comma");
						return false;
					}
					if(s.startsWith(",")) {
						if(!hadFont) {
							// There was a previous font.
							if(logDEBUG) log("out of context comma (2)");
							return false;
						}
						s = s.substring(1);
					}
					boolean endComma = false;
					if(s.endsWith(",")) {
						endComma = true;
						s = s.substring(0, s.length()-1);
					}
					String[] split;
					if(s.contains(",")) {
						split = s.split(",");
					} else
						split = new String[] { s };
					
					boolean moreWords = false;
					
					for(int j=0;j<split.length;j++) {
						String subword = split[j];
						ParsedWord[] parsed = split(subword);
						if(parsed.length != 1) {
							if(logDEBUG) log("subword "+subword+" from "+s+" cannot parse into one word");
							return false;
						}
						String keyword;
						// Decode it, strip quotes etc.
						if(parsed[0] instanceof ParsedString) {
							keyword = (((ParsedString)parsed[0]).getDecoded());
						} else if(parsed[0] instanceof ParsedIdentifier) {
							keyword = (((ParsedIdentifier)parsed[0]).getDecoded());
						} else {
							// Unquoted, so not safe to allow wierd characters.
							if(logDEBUG) log("subword "+subword+" from "+s+" parses to unrecognised type "+parsed[0]);
							return false;
						}
						keyword = keyword.toLowerCase();
						if(j != split.length-1 || endComma) {
							// This must be a complete font keyword.
							// We start from a position of not being in a font, fontWords is empty (the main loop: the loop below continues until it finds a font).
							if(ElementInfo.isGenericFontFamily(keyword)) {
								hadFont = true;
								continue;
							} else if(ElementInfo.isSpecificFontFamily(keyword)) {
								hadFont = true;
								continue;
							} else {
								if(logDEBUG) log("not a valid token (separated by commas): "+keyword+" from "+subword+" from "+s+" in main loop");
								return false;
							}
						} else {
							// Last item, and endComma == false.
							// This means there *might* be another font keyword afterwards.
							s = keyword;
							moreWords = true;
						}
					}
					
					if(!moreWords) continue; // Everything happily eaten.
				} else
					return false;
				// Unquoted multi-word font, or unquoted single-word font.
				// Unfortunately fonts can be ambiguous...
				// Therefore we do not accept a single-word font unless it is either quoted or ends in a comma.
				fontWords.clear();
				assert(s != null);
				fontWords.add(s);
				if(logDEBUG) log("first word: \""+s+"\"");
				if(i == value.length-1) {
					if(logDEBUG) log("last word. font words: "+getStringFromArray(fontWords.toArray(new String[fontWords.size()]))+" valid="+validFontWords(fontWords));
					return validFontWords(fontWords);
				}
				if(!possiblyValidFontWords(fontWords))
					return false;
				boolean last = false;
				ParsedWord w = null;
				for(int j=i+1;j<value.length;j++) {
					ParsedWord newWord = value[j];
					if(w != null) newWord = w;
					w = null;
					if (j == value.length-1) last = true;
					String s1;
					if(newWord instanceof SimpleParsedWord) {
						s1 = ((SimpleParsedWord)newWord).original;
						
						if(s1.equals(",")) {
							if(validFontWords(fontWords)) {
								fontWords.clear();
								hadFont = true;
								i = j;
								continue outer;
							} else {
								if(logDEBUG) log("comma but can't parse font words: "+fontWords.toArray(new String[fontWords.size()]));
								return false;
							}
						}
						
						if(s1.startsWith(",")) {
							if(!validFontWords(fontWords)) {
								if(logDEBUG) log("comma but can't parse font words (2): "+fontWords.toArray(new String[fontWords.size()]));
								return false;
							}
							fontWords.clear();
							s1 = s1.substring(1);
						}
						
						boolean endComma = false;
						
						if(s1.endsWith(",")) {
							endComma = true;
							s1 = s1.substring(0, s1.length()-1);
						}
						
						String[] split;
						if(s1.contains(",")) {
							split = s1.split(",");
						} else
							split = new String[] { s1 };
						
						for(int k=0;k<split.length;k++) {
							String subword = split[k];
							ParsedWord[] parsed = split(subword);
							if(parsed.length != 1) {
								if(logDEBUG) log("subword "+subword+" from "+s1+" cannot parse into one word");
								return false;
							}
							String keyword;
							// Decode it, strip quotes etc.
							if(parsed[0] instanceof ParsedString) {
								keyword = (((ParsedString)parsed[0]).getDecoded());
							} else if(parsed[0] instanceof ParsedIdentifier) {
								keyword = (((ParsedIdentifier)parsed[0]).getDecoded());
							} else {
								// Unquoted, so not safe to allow wierd characters.
								if(logDEBUG) log("subword "+subword+" from "+s1+" parses to unrecognised type "+parsed[0]);
								return false;
							}
							keyword = keyword.toLowerCase();
							if(logDEBUG) log("keyword "+k+" of "+split.length+" is \""+keyword+"\"");
							
							if(k == 0 && (split.length > 0 || endComma)) {
								fontWords.add(keyword);
								if(!validFontWords(fontWords)) {
									log("first item in "+s+" is "+subword+" gives font words "+getStringFromArray(fontWords.toArray(new String[fontWords.size()])));
									return false;
								}
								fontWords.clear();
							} else if(k == 0 && split.length == 1 && !endComma) {
								fontWords.add(keyword);
							} else if(k != split.length-1 || endComma) {
								// This must be a complete font keyword.
								// We start from a position of not being in a font, fontWords is empty (the main loop: the loop below continues until it finds a font).
								if(ElementInfo.isGenericFontFamily(keyword)) {
									hadFont = true;
									continue;
								} else if(ElementInfo.isSpecificFontFamily(keyword)) {
									hadFont = true;
									continue;
								} else {
									if(logDEBUG) log("not a valid token (separated by commas): "+keyword+" from "+subword+" from "+s1+" in second loop");
									return false;
								}
							} else {
								// Last item, and endComma == false.
								// This means there *might* be another font keyword afterwards.
								fontWords.add(keyword);
							}
						}
						
						if(fontWords.isEmpty()) {
							if(logDEBUG) log("fontWords empty, continuing outer loop");
							i = j;
							continue outer; // Everything happily eaten.
						} // Else looking for another keyword
					} else if(newWord instanceof ParsedIdentifier) {
						s1 = ((ParsedIdentifier)newWord).getDecoded();
						fontWords.add(s1);
						if(logDEBUG) log("adding word: \""+s1+"\"");
						if(last) {
							if(validFontWords(fontWords)) {
								// Valid. Good.
								if(logDEBUG) log("font: reached last in inner loop, valid. font words: "+getStringFromArray(fontWords.toArray(new String[fontWords.size()])));
								return true;
							}
						}
					} else {
						if(logDEBUG) log("cannot parse "+newWord);
						return false;
					}
				}
				// Still looking for another keyword...
				if(validFontWords(fontWords))
					return true;
				return false;
			}
			if(logDEBUG) log("font: reached end, valid");
			return true;
			}

		private boolean possiblyValidFontWords(ArrayList<String> fontWords) {
			if(ElementInfo.disallowUnknownSpecificFonts) {
				StringBuffer sb = new StringBuffer();
				boolean first = true;
				for(String s : fontWords) {
					if(!first) sb.append(' ');
					first = false;
					sb.append(s);
				}
				String s = sb.toString().toLowerCase();
				return ElementInfo.isWordPrefixOrMatchOfSpecificFontFamily(s);
			} else {
				for(String s : fontWords)
					if(!ElementInfo.isSpecificFontFamily(s)) return false;
				return true;
			}
		}

		private boolean validFontWords(ArrayList<String> fontWords) {
			for(String s : fontWords) {
				if(s == null) throw new NullPointerException(); 
			}
			if(fontWords.size() == 1) {
				if(ElementInfo.isGenericFontFamily(fontWords.get(0).toLowerCase()))
					return true;
			}
			StringBuffer sb = new StringBuffer();
			boolean first = true;
			for(String s : fontWords) {
				if(!first) sb.append(' ');
				first = false;
				sb.append(s);
			}
			return ElementInfo.isSpecificFontFamily(sb.toString().toLowerCase());
		}
		
	}

	public static String removeOuterQuotes(String decoded) {
		if(decoded.length() < 2) return decoded;
		char first = decoded.charAt(0);
		if(!(first == '\'' || first == '\"')) return decoded;
		if(decoded.charAt(decoded.length()-1) == first) {
			return decoded.substring(1, decoded.length()-1);
		}
		return decoded;
	}

	public String detectedCharset() {
		return detectedCharset;
	}
}
