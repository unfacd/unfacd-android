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

package com.unfacd.android.ui.components.intro_contact;

import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;

import androidx.annotation.NonNull;

public class AppEventIntroContact extends UfsrvCommandEvent
{
  @NonNull
  private final IntroContactDescriptor contactDescriptor;
  private final Long                   msgId;

  public AppEventIntroContact(IntroContactDescriptor contact) {
    this.contactDescriptor = contact;
    this.msgId = Long.valueOf(-1);
  }

  public AppEventIntroContact(IntroContactDescriptor contact, long msgId, CommandArgs status) {
    super(status);
    this.contactDescriptor = contact;
    this.msgId = Long.valueOf(msgId);
  }

  @NonNull
  public IntroContactDescriptor getContactDescriptor () {
    return contactDescriptor;
  }

  public Long getMsgId () {
    return msgId;
  }
}