/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.*;
import java.util.ArrayList;

import freenet.support.Logger;

/**
 * WARNING: this is not as thorough as the HTML filter - we do not
 * enumerate all possible attributes etc. New versions of the spec could
 * conceivably lead to new risks How this would happen: a) Another way to
 * include URLs, apart from @import and url() (we are safe from new @
 * directives though) b) A way to specify the MIME type of includes, IF
 * those includes could be a risky type (HTML, CSS, etc) This is still FAR
 * more rigorous than the old filter though.
 * <p>
 * If you want extra paranoia, turn on paranoidStringCheck, which will
 * throw an exception when it encounters strings with colons in; then the
 * only risk is something that includes, and specifies the type of, HTML,
 * XML or XSL.
 * </p>
 */
class CSSParser extends CSSTokenizerFilter {

	final FilterCallback cb;
	
	CSSParser(
		Reader r,
		Writer w,
		boolean paranoidStringCheck,
		FilterCallback cb,
		String charset,
		boolean stopAtDetectedCharset) {
		super(r, w, cb, charset, stopAtDetectedCharset);
		this.cb = cb;
	}

/*
	@Override
	void log(String s) {
		if (Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, s);
		
	}

	@Override
	void logError(String s) {
		Logger.error(this, s);
	}
	*/
	public static void main(String[] params)
	{
		
		try{
	    	BufferedReader input = new BufferedReader(new FileReader("/home/ashish/search.css"));
	    	//BufferedWriter output=new BufferedWriter(new FileWriter("/home/ashish/searchfiltered.html"));
	    	Writer output= new BufferedWriter(new OutputStreamWriter(System.out));

	    	NullFilterCallback objtemp=new NullFilterCallback(); 
	    	CSSParser pc = new CSSParser(input, output, true, objtemp, "UTF-8", false);
			//pc.debug=false;
	    	pc.parse();
	    	
			
	    	output.close();
			
	    }
	    catch (IOException e) {
	    	System.err.println(e.getMessage());
	    }
		
	}

}