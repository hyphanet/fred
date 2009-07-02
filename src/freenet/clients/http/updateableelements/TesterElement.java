package freenet.clients.http.updateableelements;

import java.util.Timer;
import java.util.TimerTask;

import freenet.clients.http.SimpleToadletServer;
import freenet.clients.http.ToadletContext;
import freenet.support.HTMLNode;

/** A pushed element that counts up every second. Only for testing purposes. */
public class TesterElement extends BaseUpdateableElement {

	private int		status	= 0;

	ToadletContext	ctx;

	Timer			t;

	String			id;

	public TesterElement(ToadletContext ctx, String id) {
		super("div", ctx);
		this.id = id;
		this.ctx = ctx;
		init();
		t = new Timer(true);
		t.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {
				update();
			}
		}, 0, 1000);
	}

	public void update() {
		((SimpleToadletServer) ctx.getContainer()).pushDataManager.updateElement(getUpdaterId(ctx.getUniqueId()));
	}

	@Override
	public void dispose() {
		t.cancel();
	}

	@Override
	public String getUpdaterId(String requestId) {
		return "test:" + requestId + "id:" + id;
	}

	@Override
	public String getUpdaterType() {
		return UpdaterConstants.PROGRESSBAR_UPDATER;
	}

	@Override
	public void updateState() {
		children.clear();
		addChild(new HTMLNode("div", "" + status++));
	}

}
