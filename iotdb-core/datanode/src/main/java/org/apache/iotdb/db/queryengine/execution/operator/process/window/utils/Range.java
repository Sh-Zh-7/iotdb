package org.apache.iotdb.db.queryengine.execution.operator.process.window.utils;

public class Range {
  private final int start;
  private final int end;

  public Range(int start, int end) {
    this.start = start;
    this.end = end;
  }

  public int getStart() {
    return start;
  }

  public int getEnd() {
    return end;
  }
}