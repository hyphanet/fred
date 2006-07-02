package freenet.client.async;

import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;

public class PrioritySchedulerCallback implements StringCallback{
	String value;
	ClientRequestScheduler cs;
	
	PrioritySchedulerCallback(ClientRequestScheduler cs){
		this.value = new String(ClientRequestScheduler.PRIORITY_HARD);
		this.cs = cs;
	}
	
	public String get(){
		return value;
	}
	
	public void set(String val) throws InvalidConfigValueException{
		if(val.equalsIgnoreCase(get())) return;
		if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
			value = ClientRequestScheduler.PRIORITY_HARD;
		}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
			value = ClientRequestScheduler.PRIORITY_SOFT;
		}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_NONE)){
			value = ClientRequestScheduler.PRIORITY_NONE;
		}else{
			throw new InvalidConfigValueException("The value "+val+" isn't valid.");
		}
		cs.setPriorityScheduler(value);
	}
}