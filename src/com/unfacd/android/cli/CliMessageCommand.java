package com.unfacd.android.cli;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;

import org.thoughtcrime.securesms.logging.Log;

import java.util.List;

@Command(name = "msg", description = "We're just getting started")
public class CliMessageCommand implements CliRunnable {
  static private final String TAG = CliMessageCommand.class.getSimpleName();

  @Option(name = { "-f", "--flag" }, description = "An option that requires no values")
  private boolean flag = false;

  @Arguments(description = "Additional arguments")
  private List<String> args;

//  public static void main(String[] args) {
//    SingleCommand<CliMessageCommand> parser = SingleCommand.singleCommand(CliMessageCommand.class);
//    CliMessageCommand cmd = parser.parse(args);
//    cmd.run();
//  }

  @Override
  public int run()  {
//    System.out.println("Flag was " + (this.flag ? "set" : "not set"));
//    if (args != null)
//      System.out.println("Arguments were " + StringUtils.join(args, ","));
    Log.d(TAG, String.format("CliCommand: '%s'",  Stream.of(args).collect(Collectors.joining(" " ))));

    return 0;
  }
}