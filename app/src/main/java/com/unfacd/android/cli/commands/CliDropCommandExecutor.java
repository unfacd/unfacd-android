package com.unfacd.android.cli.commands;

import android.content.Context;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.unfacd.android.cli.CliParserRegistry;
import com.unfacd.android.jobs.MessageEffectsJob;

import org.thoughtcrime.securesms.components.emoji.EmojiProvider;
import org.thoughtcrime.securesms.conversation.ConversationParentFragment;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.List;

public class CliDropCommandExecutor implements CliParserRegistry.CliExecutor
{
  @Override
  public ConversationParentFragment.CliExecutorState execute(Context context, Recipient groupRecipient, List<String> args)
  {
    String flattenedArgs = Stream.of(args).collect(Collectors.joining(" " ));
    if (isAllEmoji(context, flattenedArgs)) {
      MessageEffectsJob.sendMessageEffect(context, groupRecipient.getUfsrvId(),flattenedArgs);
      return ConversationParentFragment.CliExecutorState.PROCESSED;
    }

    return ConversationParentFragment.CliExecutorState.PROCESS_INPUT;
  }

  static public  boolean isAllEmoji(Context context, String args)
  {
    return (EmojiProvider.isAllEmojiText(args));

  }
}
