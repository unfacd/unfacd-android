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

package com.unfacd.android.ufsrvcmd;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

public abstract class UfsrvCommandEvent
{
  protected int                             eventName;
  /**
   * Ufsrv command status as returned by server. Defaults to SYNCED, signalling a change in state
   */
  protected SignalServiceProtos.CommandArgs status;
  protected int                             commandArgsError;

  public UfsrvCommandEvent() {
    this.status = SignalServiceProtos.CommandArgs.SYNCED;
  }

  public UfsrvCommandEvent(SignalServiceProtos.CommandArgs status) {
    this.status = status;
  }

  public int getEventName() {
    return eventName;
  }

  public SignalServiceProtos.CommandArgs getStatus () {
    return status;
  }
}
