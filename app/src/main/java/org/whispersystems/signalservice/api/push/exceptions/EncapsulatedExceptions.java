/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.signalservice.api.push.exceptions;

import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;

import java.util.LinkedList;
import java.util.List;

public class EncapsulatedExceptions extends Throwable {

  private final List<UntrustedIdentityException> untrustedIdentityExceptions;
  private final List<UnregisteredUserException>  unregisteredUserExceptions;
  private final List<NetworkFailureException>    networkExceptions;

  public EncapsulatedExceptions(List<UntrustedIdentityException> untrustedIdentities,
                                List<UnregisteredUserException> unregisteredUsers,
                                List<NetworkFailureException> networkExceptions)
  {
    this.untrustedIdentityExceptions = untrustedIdentities;
    this.unregisteredUserExceptions  = unregisteredUsers;
    this.networkExceptions           = networkExceptions;
  }

  public EncapsulatedExceptions(UntrustedIdentityException e) {
    this.untrustedIdentityExceptions = new LinkedList<>();
    this.unregisteredUserExceptions  = new LinkedList<>();
    this.networkExceptions           = new LinkedList<>();

    this.untrustedIdentityExceptions.add(e);
  }

  public List<UntrustedIdentityException> getUntrustedIdentityExceptions() {
    return untrustedIdentityExceptions;
  }

  public List<UnregisteredUserException> getUnregisteredUserExceptions() {
    return unregisteredUserExceptions;
  }

  public List<NetworkFailureException> getNetworkExceptions() {
    return networkExceptions;
  }
}
