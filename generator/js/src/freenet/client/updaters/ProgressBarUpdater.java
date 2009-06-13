package freenet.client.updaters;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;

public class ProgressBarUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		if (content.compareTo(UpdaterConstants.FINISHED) == 0) {
			FreenetJs.log("reloading");
			reloadPage();
			FreenetJs.log("reloaded");
		} else {
			super.updated(elementId, content);
		}
	}

	private native void reloadPage()/*-{
									$wnd.location.reload();
									}-*/;

}
