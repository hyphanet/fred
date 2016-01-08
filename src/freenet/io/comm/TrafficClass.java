package freenet.io.comm;

/**
 * Represents the Traffic Class as set in @see Socket.setTrafficClass(int)
 * @see https://en.wikipedia.org/wiki/Differentiated_services
 */
public enum TrafficClass {

  BEST_EFFORT(0),
  DSCP_CRITICAL(0xB8),
  DSCP_AF11(0x28),
  DSCP_AF12(0x30),
  DSCP_AF13(0x38),
  DSCP_AF21(0x48),
  DSCP_AF22(0x50),
  DSCP_AF23(0x52),
  DSCP_AF31(0x58),
  DSCP_AF32(0x70),
  DSCP_AF33(0x78),
  DSCP_AF41(0x88),
  DSCP_AF42(0x90),
  DSCP_AF43(0x98),
  CS0(0),
  CS1(0x20),
  CS2(0x40),
  CS3(0x60),
  CS4(0x80),
  CS5(0xA0),
  CS6(0xC0),
  CS7(0xE0);

  public final int value;

  TrafficClass(int tc) {
    value = tc;
  }

  public static TrafficClass getDefault() {
    // That's high-throughput, high drop probability
    return TrafficClass.CS1;
  }

  public static TrafficClass fromNameOrValue(String tcName) {
    int tcParsed = -1;
    try {
      tcParsed = Integer.parseInt(tcName);
    } catch (NumberFormatException e){
      // it's fine; we might have a name here
    }

    for (TrafficClass t : TrafficClass.values()) {
      if (t.toString().equalsIgnoreCase(tcName) || t.value == tcParsed) {
        return t;
      }
    }
    throw new IllegalArgumentException();
  }
}
