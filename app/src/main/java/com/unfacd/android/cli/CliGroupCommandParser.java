package com.unfacd.android.cli;

import com.github.rvesse.airline.SingleCommand;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;

import java.util.List;

@Command(name = CliParserRegistry.CLI_GROUP, description = "We're just getting started")
public class CliGroupCommandParser implements CliRunnable {

  @Option(name = { "-f", "--flag" }, description = "An option that requires no values")
  private boolean flag = false;

  @Arguments(description = "Additional arguments")
  private List<String> args;

  @Override
  public CliParserContext run()  {
//    System.out.println("Flag was " + (this.flag ? "set" : "not set"));
//    if (args != null)
//      System.out.println("Arguments were " + StringUtils.join(args, ","));
    return new CliParserContext(CliParserRegistry.getInstance().CliParserMap.get(CliParserRegistry.CLI_GROUP), args);
  }
}