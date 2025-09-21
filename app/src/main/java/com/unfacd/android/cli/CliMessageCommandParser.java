package com.unfacd.android.cli;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;

import org.signal.core.util.logging.Log;

import java.util.List;

@Command(name = CliParserRegistry.CLI_MSG, description = "We're just getting started")
public class CliMessageCommandParser implements CliRunnable {
  static private final String TAG = Log.tag(CliMessageCommandParser.class);

  @Option(name = { "-f", "--flag" }, description = "An option that requires no values")
  private boolean flag = false;

  @Arguments(description = "Additional arguments")
  private List<String> args;

  @Override
  public CliParserContext run()  {
    Log.d(TAG, String.format("CliCommand (flag:'%s'): '%s'",  this.flag ? "set" : "not set", Stream.of(args).collect(Collectors.joining(" " ))));

    return new CliParserContext(CliParserRegistry.getInstance().CliParserMap.get(CliParserRegistry.CLI_MSG), args);
  }
}