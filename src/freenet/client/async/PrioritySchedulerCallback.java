package freenet.client.async;

import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;

public class PrioritySchedulerCallback implements StringCallback{
	ClientRequestScheduler cs;
	
	PrioritySchedulerCallback(ClientRequestScheduler cs){
		this.cs = cs;
	}
	
	public String get(){
		return cs.getChoosenPriorityScheduler();
	}
	
	public void set(String val) throws InvalidConfigValueException{
		String value;
		if(val.equalsIgnoreCase(get())) return;
		if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
			value = ClientRequestScheduler.PRIORITY_HARD;
		}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
			value = ClientRequestScheduler.PRIORITY_SOFT;
		}else{
			throw new InvalidConfigValueException("Invalid priority scheme");
		}
		cs.setPriorityScheduler(value);
	}
}