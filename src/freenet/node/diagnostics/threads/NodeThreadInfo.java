/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.diagnostics.threads;

/** Class to wrap node thread information. */
public class NodeThreadInfo {
  private final long id;
  private final long jobId;
  private final String name;
  private final int prio;
  private final String groupName;
  private final String state;
  private final long cpuTime;

  /**
   * @param id Thread ID
   * @param jobId Job ID
   * @param cpuTime Thread's CPU time in nanoseconds (delta)
   * @param name Thread name, or <noname>
   * @param prio Thread priority
   * @param groupName Thread's group name
   * @param state Thread current state (TIMED_WAITING, RUNNABLE, etc)
   */
  NodeThreadInfo(
      long id, long jobId, long cpuTime, String name, int prio, String groupName, String state) {
    this.id = id;
    this.jobId = jobId;
    this.name = name;
    this.prio = prio;
    this.groupName = groupName;
    this.state = state;
    this.cpuTime = cpuTime;
  }

  public long getId() {
    return id;
  }

  public long getJobId() {
    return jobId;
  }

  public String getName() {
    return name;
  }

  public int getPrio() {
    return prio;
  }

  public String getGroupName() {
    return groupName;
  }

  public String getState() {
    return state;
  }

  public long getCpuTime() {
    return cpuTime;
  }
}
