/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.whispersystems.signalservice.api.messages.calls;

import com.unfacd.android.ApplicationContext;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

public class UfsrvCallCommand
{
  CallCommand.Builder callCommandBuilder;
  Recipient           remoteRecipient;
  long                groupFid;

  public UfsrvCallCommand (Recipient remoteRecipient, long groupFid, CallCommand.CommandTypes commandType)
  {
    this.remoteRecipient    = remoteRecipient;
    this.groupFid           = groupFid;
    this.callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(),
                                System.currentTimeMillis(),
                                commandType, remoteRecipient, this.groupFid);

  }

  public CallCommand.Builder getCallCommandBuilder ()
  {
    return callCommandBuilder;
  }

  public long getGroupFid ()
  {
    return groupFid;
  }

  public Recipient getRemoteRecipient ()
  {
    return remoteRecipient;
  }
}
