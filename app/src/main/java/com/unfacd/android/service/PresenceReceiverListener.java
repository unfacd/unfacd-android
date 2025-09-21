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

package com.unfacd.android.service;

import android.content.Context;
import android.content.Intent;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.jobs.PresenceReceiverJob;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.service.PersistentAlarmManagerListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.concurrent.TimeUnit;

public class PresenceReceiverListener extends PersistentAlarmManagerListener
{

  private static final long INTERVAL = TimeUnit.SECONDS.toMillis(15);

  @Override
  protected long getNextScheduledExecutionTime(Context context) {
    return TextSecurePreferences.getPresenceReceiveTime(context);
  }

  @Override
  protected long onAlarm(Context context, long scheduledTime) {
    if (scheduledTime != 0) {
      ApplicationDependencies.getJobManager().add(new PresenceReceiverJob(null));
    }

    long newTime = System.currentTimeMillis() + INTERVAL;
    TextSecurePreferences.setPresenceReceiveTime(context, newTime);

    return newTime;
  }

  public static void schedule(Context context) {
    new PresenceReceiverListener().onReceive(context, new Intent());
  }
}