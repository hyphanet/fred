package freenet.client.updaters;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;

/** This Updater is to replace the progress bar at the progress page. It simply replaces it, but if the content is {@link UpdaterConstants.FINISHED}, then it will reload the page */
public class ProgressBarUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		try {
			FreenetJs.log("Progressbarupdater updating");
			// If FINISHED, then reload the page
			if (content.compareTo(UpdaterConstants.FINISHED) == 0) {
				FreenetJs.log("reloading");
				reloadPage();
				FreenetJs.log("reloaded");
			} else {
				// If not, then simply replace the element with the new content
				super.updated(elementId, content);
			}
		} catch (Exception e) {
			FreenetJs.log("Error occured while updating progressbarupdater: "+e);
		} finally {
			FreenetJs.log("Progressbar updater done updating");
		}
	}

	/** the native function to reload the page */
	private native void reloadPage()/*-{
									$wnd.location.reload();
									}-*/;

}
