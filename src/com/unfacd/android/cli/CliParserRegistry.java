package com.unfacd.android.cli;


import com.github.rvesse.airline.annotations.Cli;

@Cli(name = "basic",
        description = "Provides a basic example CLI",
        defaultCommand = CliMessageCommand.class,
        commands = { CliMessageCommand.class, CliGroupCommand.class })
public class CliParserRegistry {
  static private final String TAG = CliParserRegistry.class.getSimpleName();

  private static CliParserRegistry instance = null;
  com.github.rvesse.airline.Cli<CliRunnable> cli;

  public static CliParserRegistry getInstance ()
  {
    if (instance != null) {
      return instance;
    } else {
      instance = new CliParserRegistry();
      instance.cli = new com.github.rvesse.airline.Cli<>(CliParserRegistry.class);
      return instance;
    }

  }

  public int parseCli (String args) {
    CliRunnable cmd = instance.cli.parse(args);
    return cmd.run();
  }

}