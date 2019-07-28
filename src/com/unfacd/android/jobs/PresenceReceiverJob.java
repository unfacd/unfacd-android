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

package com.unfacd.android.jobs;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.unfacd.android.data.json.JsonEntityPresenceInformation;
import com.unfacd.android.data.model.UserIdList;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class PresenceReceiverJob extends BaseJob {
  private static final String KEY_UFSRVUID = "uid";

  public static final String KEY = "PresenceReceiverJob";

  private static final String TAG = PresenceReceiverJob.class.getSimpleName();

  @Nullable private Recipient    recipient = null;

  private PresenceReceiverJob(@NonNull Job.Parameters parameters, Recipient recipient) {
    super(parameters);
    this.recipient        = recipient;
  }

  public PresenceReceiverJob(@Nullable Recipient recipient)
  {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         recipient);

  }

  @Override
  public @NonNull
  Data serialize() {
    if (recipient!=null) {
      return new Data.Builder().putString(KEY_UFSRVUID, recipient.getUfsrvUid()).build();
    } else {
      return new Data.Builder().putString(KEY_UFSRVUID, UfsrvUid.UndefinedUfsrvUid).build();
    }
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    PowerManager          powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    PowerManager.WakeLock wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Presence Receiver");

    try {
      wakeLock.acquire();
      if (recipient == null) {
        LinkedList<Long> sharingUserList = getUsersWithPresenceShared(context);
        if (sharingUserList.size()>0) {
          List<JsonEntityPresenceInformation> presenceList =  ApplicationDependencies.getSignalServiceAccountManager().getPresenceUpdate(new UserIdList(sharingUserList));
          processPresenceInformation (context, presenceList);
        }
      } else {
        //fetch presence for specific user
      }
    } finally {
      if (wakeLock.isHeld()) wakeLock.release();
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {}


  /**
   * @param contex
   * @return list of users who have flagged to share their presence information with us
   */
  LinkedList<Long> getUsersWithPresenceShared (Context contex)
  {
    LinkedList<Long> sharingUsersList = new LinkedList<>();
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(contex);
    RecipientDatabase.PresenceSharedReader reader   = recipientDatabase.readerForPresenceShared(recipientDatabase.getPresenceShared());

    Recipient recipient;

    while ((recipient = reader.getNext()) != null) {
      if (!recipient.isGroupRecipient()) {
        sharingUsersList.add(recipient.getUfsrvId());
      }
    }

    reader.close();
    return sharingUsersList;
  }

  void processPresenceInformation (Context context, List<JsonEntityPresenceInformation> presenceInformationList)
  {
    Recipient recipient;
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);

    for (JsonEntityPresenceInformation presenceInformation: presenceInformationList) {
      switch (presenceInformation.getStatus()) {
        case 1:
          recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(presenceInformation.getUfsrvuid()), false);
          recipientDatabase.setPresenceInformation(recipient, String.format("%d,%d", presenceInformation.getStatus(), presenceInformation.getServiced()));
          break;

        case 2:
          recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(presenceInformation.getUfsrvuid()), false);
          recipientDatabase.setPresenceInformation(recipient, String.format("%d,%d", presenceInformation.getStatus(), presenceInformation.getSuspended()));
          break;

        case 0:
          break;
      }
    }
  }

  void JsonFormatSharingList (LinkedList<Long> sharingUsersList)
  {
    if (sharingUsersList.size()>0)
    {

    }
  }

  public static class Factory implements Job.Factory<PresenceReceiverJob> {
    private final Application application;

    public Factory(Application application) {
      this.application = application;
    }

    @Override
    public @NonNull PresenceReceiverJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PresenceReceiverJob(parameters,  null);//Recipient.from()data.getString(KEY_UFSRVUID));//TODO pass ufsrvud inread of recipinet
    }
  }
}