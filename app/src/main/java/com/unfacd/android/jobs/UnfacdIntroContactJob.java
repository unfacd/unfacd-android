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

import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.utils.IntroMessageUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;

import androidx.annotation.NonNull;

public class UnfacdIntroContactJob extends BaseJob {
  public static final String KEY = "UnfacdIntroContactJob";

  private static final String KEY_INTRO_ID        = "INTRO_ID";

  private static final String TAG = Log.tag(UnfacdIntroContactJob.class);

  private long introId = -1;
  Optional<Pair<Long, IntroContactDescriptor>> descriptor = Optional.empty();

  private UnfacdIntroContactJob (@NonNull Job.Parameters parameters, long introId) {
    super(parameters);
    this.introId = introId;
    descriptor =  SignalDatabase.unfacdIntroContacts().getIntroContact(this.introId);
  }

  public UnfacdIntroContactJob (long introId) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         introId);

  }
  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException
  {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    IntroMessageUtils.sendContactIntroMessage(context, descriptor.get());
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public @NonNull
  Data serialize() {
    Data.Builder builder = new Data.Builder();

    return builder.putLong(KEY_INTRO_ID, introId).build();
  }

  @Override
  public void onFailure() {

  }

  public static class Factory implements Job.Factory<UnfacdIntroContactJob> {
    @Override
    public @NonNull
    UnfacdIntroContactJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new UnfacdIntroContactJob(parameters, data.getLong(KEY_INTRO_ID));
    }
  }

}
