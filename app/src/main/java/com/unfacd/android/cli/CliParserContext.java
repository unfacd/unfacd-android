package com.unfacd.android.cli;

import android.content.Context;

import org.thoughtcrime.securesms.conversation.ConversationParentFragment;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.List;

public class CliParserContext
{
  private CliParserRegistry.CliExecutor executor;
  private List<String> args;

  public CliParserContext (CliParserRegistry.CliExecutor executor, List<String> args)
  {
    this.executor = executor;
    this.args = args;
  }

  public ConversationParentFragment.CliExecutorState execute (Context context, Recipient groupRecipient)
  {
    return this.executor.execute(context, groupRecipient, args);
  }

  public CliParserRegistry.CliExecutor getExecutor ()
  {
    return executor;
  }

  public List<String> getArgs ()
  {
    return args;
  }

  public boolean isEmptyExecutor ()
  {
    return executor == null;
  }
}