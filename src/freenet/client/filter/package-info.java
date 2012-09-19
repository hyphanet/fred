/**
 * Freenet content filter code. The purpose of this code, which can 
 * optionally be invoked during a download (and is mainly used in fproxy),
 * is to identify safe content, and delete or warn about any content that
 * either we don't understand, or we can't currently make safe.
 * 
 * Dangerous content here mainly means content (e.g. HTML) that could cause 
 * the browser to do fetches from the non-anonymous web ("web bugs"), or more 
 * complex attacks that achieve the same result (e.g. scripting). We do not
 * intentionally prohibit specific exploits, unless it is easy to do so, but
 * we do operate on a "whitelist" principle as much as possible (especially
 * in the HTML and CSS filters), where we parse the data, and if we can't
 * understand it, we delete it, because it might be dangerous. This is much
 * safer than e.g. looking for things that look like URLs.
 * 
 * We also have a registry of known MIME types, with either filters or 
 * warning messages.
 * 
 * Finally, we support some degree of "write filtering", i.e. stripping out
 * potentially compromising data (e.g. EXIF tags) at insert time. And we
 * support arbitrary transformations of HTML tags via @see TagReplacerCallback
 * which is used by fproxy for showing loading images etc.
 * 
 * @see freenet.client.async.ClientGetWorkerThread (driver thread)
 * @see freenet.clients.http.FproxyToadlet (major user of this code 
 * practically speaking)
 */
package freenet.client.filter;