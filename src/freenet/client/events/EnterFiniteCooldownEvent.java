package freenet.client.events;

import freenet.support.TimeUtil;

public class EnterFiniteCooldownEvent implements ClientEvent {

	public final long wakeupTime;
	
	static final int CODE = 0x10;
	
	public EnterFiniteCooldownEvent(long wakeupTime) {
		this.wakeupTime = wakeupTime;
	}

	@Override
	public String getDescription() {
		return "Wake up in "+TimeUtil.formatTime(wakeupTime - System.currentTimeMillis(), 2, true);
	}

	@Override
	public int getCode() {
		return CODE;
	}

}
