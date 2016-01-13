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
  DSCP_CS0(0),
  DSCP_CS1(0x20),
  DSCP_CS2(0x40),
  DSCP_CS3(0x60),
  DSCP_CS4(0x80),
  DSCP_CS5(0xA0),
  DSCP_CS6(0xC0),
  DSCP_CS7(0xE0),
  RFC1349_IPTOS_LOWCOST(0x02),
  RFC1349_IPTOS_RELIABILITY(0x04),
  RFC1349_IPTOS_THROUGHPUT(0x08),
  RFC1349_IPTOS_LOWDELAY(0x10);

  public final int value;

  TrafficClass(int tc) {
    value = tc;
  }

  public static TrafficClass getDefault() {
    // That's high-throughput, high drop probability
    return TrafficClass.DSCP_CS1;
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
