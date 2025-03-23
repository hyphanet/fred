package freenet.node.useralerts;

import freenet.support.HTMLNode;

/**
 * Wrapper class to create anonymous classes with interface NodeToNodeMessageUserAlert
 */
public abstract class AbstractNodeToNodeFileOfferUserAlert extends AbstractUserAlert implements NodeToNodeMessageUserAlert {
  public AbstractNodeToNodeFileOfferUserAlert(){
    super();
  }
  
	protected AbstractNodeToNodeFileOfferUserAlert(boolean userCanDismiss, String title, String text, String shortText, HTMLNode htmlText, short priorityClass, boolean valid, String dismissButtonText, boolean shouldUnregisterOnDismiss, Object userIdentifier) {
    super(userCanDismiss, title, text, shortText, htmlText, priorityClass, valid, dismissButtonText, shouldUnregisterOnDismiss, userIdentifier);
	}
  
}
