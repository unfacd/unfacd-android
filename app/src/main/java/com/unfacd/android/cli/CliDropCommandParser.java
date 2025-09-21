package com.unfacd.android.cli;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;

import org.signal.core.util.logging.Log;

import java.util.Arrays;
import java.util.List;

@Command(name = CliParserRegistry.CLI_DROP, description = "Drop emojis")
public class CliDropCommandParser implements CliRunnable {
  static private final String TAG = Log.tag(CliDropCommandParser.class);

  @Option(name = { "-f", "--flag" }, description = "An option that requires no values")
  private boolean flag = false;

  @Arguments(description = "Additional arguments")
  private List<String> args;

  @Override
  public CliParserContext run()  {
    Log.d(TAG, String.format("CliCommand DROP (flag:'%s': '%s'", this.flag ? "set" : "not set", Stream.of(args).collect(Collectors.joining(" " ))));
    //CliParserRegistry.getInstance().CliParserMap.get("drop").parse(Stream.of(args).collect(Collectors.joining(" " )));

    String flattenedArgs = Stream.of(args).collect(Collectors.joining(" " ));
    String argsTokens[] = flattenedArgs.split(" ");
    return new CliParserContext(CliParserRegistry.getInstance().CliParserMap.get("drop"), Arrays.asList(argsTokens[1]));
  }
}