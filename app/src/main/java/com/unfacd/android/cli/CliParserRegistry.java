package com.unfacd.android.cli;


import android.content.Context;

import com.github.rvesse.airline.HelpOption;
import com.github.rvesse.airline.annotations.Cli;
import com.github.rvesse.airline.annotations.Parser;
import com.github.rvesse.airline.help.Help;
import com.github.rvesse.airline.model.GlobalMetadata;
import com.github.rvesse.airline.parser.ParseResult;
import com.github.rvesse.airline.parser.errors.ParseException;
import com.github.rvesse.airline.parser.errors.handlers.CollectAll;
import com.github.rvesse.airline.parser.options.ListValueOptionParser;
import com.unfacd.android.cli.commands.CliDropCommandExecutor;

import org.thoughtcrime.securesms.conversation.ConversationParentFragment;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

@Cli(name = "basic",
        description = "Provides a basic example CLI",
        commands = { CliMessageCommandParser.class, CliGroupCommandParser.class, CliDropCommandParser.class, Help.class },
        defaultCommand = CliDropCommandParser.class,
        parserConfiguration = @Parser(
                useDefaultOptionParsers = true,
                defaultParsersFirst = false,
                optionParsers = { ListValueOptionParser.class },
                errorHandler = CollectAll.class
        ))
public class CliParserRegistry {
  static private final String TAG = Log.tag(CliParserRegistry.class);
  public final static String CliPrefix = ",";

  public final static String CLI_DROP = "drop";
  public final static String CLI_GROUP = "group";
  public final static String CLI_MSG = "msg";

  HashMap<String, CliExecutor> CliParserMap = new HashMap<>();

  private static CliParserRegistry instance = null;

  com.github.rvesse.airline.Cli<CliRunnable> cli;

  @Inject
  private HelpOption<CliParserRegistry> help;

  public static CliParserRegistry getInstance ()
  {
    if (instance != null) {
      return instance;
    } else {
      instance = new CliParserRegistry();
      instance.cli = new com.github.rvesse.airline.Cli<>(CliParserRegistry.class);
      instance.registerCommandCallbacks();
      return instance;
    }

  }

  private void registerCommandCallbacks ()
  {
    /*GlobalMetadata commandsMetadata = this.cli.getMetadata();
    List<CommandGroupMetadata> commands = commandsMetadata.getCommandGroups();
    for (CommandGroupMetadata commandMetadata : commands ) {
      commandMetadata.getGroupName();
    }*/

    registerCliCallback (CLI_DROP, new CliDropCommandExecutor());

  }

  public CliParserContext getParser (String args) {
//    CliRunnable cmd = instance.cli.parse(args);
//    return cmd.run();
    GlobalMetadata m = this.cli.getMetadata();
    try {
      ParseResult<CliRunnable> result = instance.cli.parseWithResult(args);
      if (result.wasSuccessful()) {
        return result.getCommand().run();
      } else {
        Log.d(TAG, String.format("getParser: %d errors encountered:", result.getErrors().size()));
        int i = 1;
        for (ParseException e : result.getErrors()) {
          Log.d(TAG, (String.format("Error %d: %s", i, e.getMessage())));
          i++;
        }
      }
    } catch (Exception e) {
      Log.d(TAG, e.getMessage());
    }

   return new CliParserContext(null, new LinkedList<>());
  }

  public void registerCliCallback (String commandName, CliExecutor parser)
  {
      CliParserMap.put(commandName, parser);
  }

  public interface CliExecutor
  {
    ConversationParentFragment.CliExecutorState execute (Context context, Recipient groupRecipient, List<String> args);
  }


}