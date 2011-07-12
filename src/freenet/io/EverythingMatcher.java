package freenet.io;

import java.net.InetAddress;

public class EverythingMatcher implements AddressMatcher {
	public EverythingMatcher() {
	}
	
	@Override
	public boolean matches(InetAddress address) {
		return true;
	}

	@Override
	public String getHumanRepresentation() {
		return "*";
	}

}
