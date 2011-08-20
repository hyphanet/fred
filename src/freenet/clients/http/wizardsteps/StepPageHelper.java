package freenet.clients.http.wizardsteps;

import com.sun.org.apache.xerces.internal.dom.ParentNode;
import com.sun.org.apache.xml.internal.dtm.ref.DTMDefaultBaseIterators;
import com.sun.xml.internal.ws.wsdl.writer.document.soap.Header;
import freenet.clients.http.PageNode;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;
import org.omg.CORBA.CTX_RESTRICT_SCOPE;

import javax.swing.border.TitledBorder;
import java.lang.annotation.Target;
import java.util.StringTokenizer;

/**
 * Provides a page content node, forms, and InfoBoxes. Used to wrap ToadletContext access away from Wizard Steps.
 */
public class StepPageHelper {

	private final ToadletContext toadletContext;
	private PageNode pageNode;

	public StepPageHelper(ToadletContext ctx) {
		this.toadletContext = ctx;
	}

	/**
	 * Generates a PageMaker with the appropriate arguments for the wizard. (Ex hiding status and nav bars)
	 * This is so that steps can determine their page title at runtime instead of being limited to one. This is
	 * needed for the physical security page.
	 * @param title desired page title
	 * @return Content HTMLNode to add content to
	 */
	public HTMLNode getPageContent(String title) {
		pageNode = toadletContext.getPageMaker().getPageNode(title, false, false, toadletContext);
		return pageNode.content;
	}

	/**
	 * After getPageContent has been called, returns page outer HTMLNode.
	 * @return page outer node used to render entire page.
	 */
	public HTMLNode getPageOuter() {
		if (pageNode == null) {
			throw new NullPointerException("pageNode was not initialized. getPageContent must be called first.");
		}
		return pageNode.outer;
	}

	public HTMLNode getInfobox(String category, String header, HTMLNode parent, String title, boolean isUnique) {
		toadletContext.getPageMaker().getInfobox("infobox-error",
		        WizardL10n.l10n("passwordWrongTitle"), parent, null, true).
		        addChild("div", "class", "infobox-content");
		return toadletContext.getPageMaker().getInfobox(category, header, parent, title, isUnique);
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String id) {
		return toadletContext.addFormChild(parentNode, target, id);
	}
}