package freenet.client.updaters;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;

public class ProgressBarUpdater extends ReplacerUpdater {

	@Override
	public void updated(String elementId, String content) {
		try{
		FreenetJs.log("Progressbarupdater updating");
		if (content.compareTo(UpdaterConstants.FINISHED) == 0) {
			FreenetJs.log("reloading");
			reloadPage();
			FreenetJs.log("reloaded");
		} else {
			super.updated(elementId, content);
		}
		}catch(Exception e){
			FreenetJs.log("Error occured while updating progressbarupdater");
		}finally{
			FreenetJs.log("Progressbar updater done updating");
		}
	}

	private native void reloadPage()/*-{
									$wnd.location.reload();
									}-*/;

}
