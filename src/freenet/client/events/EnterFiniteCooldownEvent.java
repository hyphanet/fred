package freenet.client.events;

import freenet.support.TimeUtil;

public class EnterFiniteCooldownEvent implements ClientEvent {

	public final long wakeupTime;
	
	static final int CODE = 0x10;
	
	public EnterFiniteCooldownEvent(long wakeupTime) {
		this.wakeupTime = wakeupTime;
	}

	public String getDescription() {
		return "Wake up in "+TimeUtil.formatTime(wakeupTime - System.currentTimeMillis(), 2, true);
	}

	public int getCode() {
		return CODE;
	}

}
