package com.unfacd.android.cli;

public interface CliRunnable {

  /**
   * Runs the command and returns a result code
   *
   * @return Exit code
   */
  public int run();
}