package freenet.clients.http.geoip;

public class Cache {
	short[] codes;
	long[] ips;
	
	public Cache(short[] codes,long[] ips) {
		this.codes=codes;
		this.ips=ips;
	}
	
	public short[] getCodes(){
		return codes;
	}
	
	public long[] getIps() {
		return ips;
	}
}
