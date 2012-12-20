package freenet.clients.http.geoip;

public class Cache {
	short[] codes;
	int[] ips;
	
	public Cache(short[] codes,int[] ips) {
		this.codes=codes;
		this.ips=ips;
	}
	
	public short[] getCodes(){
		return codes;
	}
	
	public int[] getIps() {
		return ips;
	}
}
