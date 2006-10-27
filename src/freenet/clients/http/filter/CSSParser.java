/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.Reader;
import java.io.Writer;

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
		FilterCallback cb) {
		super(r, w, paranoidStringCheck);
		this.cb = cb;
		this.deleteErrors = super.deleteErrors;
	}

	void throwError(String s) throws DataFilterException {
		HTMLFilter.throwFilterException(s);
	}

	String processImportURL(String s) throws CommentException {
		return HTMLFilter.sanitizeURI(HTMLFilter.stripQuotes(s), "text/css", null, cb);
	}

	String processURL(String s) throws CommentException {
		return HTMLFilter.sanitizeURI(HTMLFilter.stripQuotes(s), null, null, cb);
	}

	void log(String s) {
		if (Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, s);
	}

	void logError(String s) {
		Logger.error(this, s);
	}
}