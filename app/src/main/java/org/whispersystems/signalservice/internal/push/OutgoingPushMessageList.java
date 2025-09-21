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
package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

public class OutgoingPushMessageList {

  @JsonProperty
  private String destination;

  @JsonProperty
  private long timestamp;

  @JsonProperty
  private List<OutgoingPushMessage> messages;

  @JsonProperty
  private boolean online;

  public OutgoingPushMessageList(String destination,
                                 long timestamp,
                                 List<OutgoingPushMessage> messages,
                                 boolean online)
  {
    this.timestamp   = timestamp;
    this.destination = destination;
    this.messages    = messages;
    this.online      = online;
  }

  public String getDestination() {
    return destination;
  }

  public List<OutgoingPushMessage> getMessages() {
    return messages;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public boolean isOnline() {
    return online;
  }

  @JsonIgnore
  public List<Integer> getDevices() {
    return messages.stream().map(OutgoingPushMessage::getDestinationDeviceId).collect(Collectors.toList());
  }
}
