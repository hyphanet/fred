/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.filter;

import java.io.IOException;
import java.util.List;

import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;

/**
 * Thrown by the filter when it cannot guarantee the safety of the data, because it is an unknown type,
 * because it cannot be filtered, or because we do not know how to filter it.
 * 
 * Base class for UnknownContentTypeException and KnownUnsafeContentTypeException.
 */
public abstract class UnsafeContentTypeException extends IOException {
	private static final long serialVersionUID = 1L;

	/**
	 * Get the contents of the error page.
	 */
	@Override
	public abstract String getMessage();

	/**
	 * Get additional details about the failure
	 */
	public List<String> details() {
		return null;
	}

	/**
	 * Get the title of the error page.
	 */
	public abstract String getHTMLEncodedTitle();
	
	/**
	 * Get the raw title of the error page. (May be unsafe for HTML).
	 */
	public abstract String getRawTitle();
	/**
	 * Get the title of the Exception
	 */
	@Override
	public String toString() {
		return getRawTitle();
	}

	/**
	 * Returns the error code that a FetchException may be instantiated with
	 * Subclasses of this exception may override this method to provide more 
	 * detailed error messages.
	 */
	public FetchExceptionMode getFetchErrorCode() {
		return FetchExceptionMode.CONTENT_VALIDATION_FAILED;
	}

	public FetchException recreateFetchException(FetchException e, String mime) {
		return new FetchException(getFetchErrorCode(), e.expectedSize, this, mime);
	}
	
	public FetchException createFetchException(String mime, long expectedSize) {
		return new FetchException(getFetchErrorCode(), expectedSize, this, mime);
	}
}
