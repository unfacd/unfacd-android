package com.unfacd.android.cli;

import com.github.rvesse.airline.SingleCommand;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;

import java.util.List;

@Command(name = "getting-started", description = "We're just getting started")
public class CliGroupCommand implements CliRunnable {

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
    return 0;
  }
}