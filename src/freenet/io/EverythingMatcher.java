package freenet.io;

import java.net.InetAddress;

public class EverythingMatcher implements AddressMatcher {
	public EverythingMatcher() {
	}
	
	public boolean matches(InetAddress address) {
		return true;
	}

	public String getHumanRepresentation() {
		return "*";
	}

}
