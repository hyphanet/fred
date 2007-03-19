package freenet.io;

import java.net.InetAddress;

import freenet.io.AddressIdentifier.AddressType;

public class EverythingMatcher implements AddressMatcher {
	
	final AddressType type;

	public EverythingMatcher(AddressType t) {
		type = t;
	}
	
	public AddressType getAddressType() {
		return type;
	}

	public boolean matches(InetAddress address) {
		return true;
	}

	public String getHumanRepresentation() {
		return "*";
	}

}
