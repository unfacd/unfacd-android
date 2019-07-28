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
package org.whispersystems.signalservice.api.messages;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.List;

//

/**
 * Group information to include in SignalServiceMessages destined to groups.
 *
 * This class represents a "context" that is included with Signal Service messages
 * to make them group messages.  There are three types of context:
 *
 * 1) Update -- Sent when either creating a group, or updating the properties
 *    of a group (such as the avatar icon, membership list, or title).
 * 2) Deliver -- Sent when a message is to be delivered to an existing group.
 * 3) Quit -- Sent when the sender wishes to leave an existing group.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceGroup {

  public enum Type {
    UNKNOWN,
    UPDATE,
    DELIVER,
    QUIT,
    REQUEST_INFO,
    UFSRV// corresponds with values in proto
  }

  private final byte[]                         groupId;
  private final Type                           type;
  private final Optional<String>               name;
  private final Optional<List<String>>         members;
  private final Optional<SignalServiceAttachment> avatar;
  //
  private final Optional<SignalServiceProtos.FenceCommand> fenceCommand;

  /**
   * Construct a DELIVER group context.
   * @param groupId
   */
  public SignalServiceGroup(byte[] groupId) {
    this(Type.DELIVER, groupId, null, null, null);
  }

  /**
   * Construct a group context.
   * @param type The group message type (update, deliver, quit).
   * @param groupId The group ID.
   * @param name The group title.
   * @param members The group membership list.
   * @param avatar The group avatar icon.
   */
  public SignalServiceGroup(Type type, byte[] groupId, String name,
                            List<String> members,
                            SignalServiceAttachment avatar)
  {
    this.type    = type;
    this.groupId = groupId;
    this.name    = Optional.fromNullable(name);
    this.members = Optional.fromNullable(members);
    this.avatar  = Optional.fromNullable(avatar);
    //
    this.fenceCommand=Optional.absent();
  }


  // to support the new fencecommand paramater
  /**
   * Construct a group context.
   * @param type The group message type (update, deliver, quit).
   * @param groupId The group ID.
   * @param name The group title.
   * @param members The group membership list.
   * @param avatar The group avatar icon.
   */
  public SignalServiceGroup(Type type, byte[] groupId, String name,
                            List<String> members,
                            SignalServiceAttachment avatar,
                            SignalServiceProtos.FenceCommand fenceCommand)
  {
    this.type    = type;
    this.groupId = groupId;
    this.name    = Optional.fromNullable(name);
    this.members = Optional.fromNullable(members);
    this.avatar  = Optional.fromNullable(avatar);
    //
    this.fenceCommand=Optional.fromNullable(fenceCommand);
  }


  public byte[] getGroupId() {
    return groupId;
  }

  public Type getType() {
    return type;
  }

  public Optional<String> getName() {
    return name;
  }

  public Optional<List<String>> getMembers() {
    return members;
  }

  public Optional<SignalServiceAttachment> getAvatar() {
    return avatar;
  }

  public static Builder newUpdateBuilder() {
    return new Builder(Type.UPDATE);
  }

  public static Builder newBuilder(Type type) {
    return new Builder(type);
  }

  //
  public Optional<SignalServiceProtos.FenceCommand> getFenceCommand() {
    return fenceCommand;
  }


  public static class Builder {

    private Type                 type;
    private byte[]               id;
    private String               name;
    private List<String>         members;
    private SignalServiceAttachment avatar;
    //
    private SignalServiceProtos.FenceCommand fenceCommand;

    private Builder(Type type) {
      this.type = type;
    }

    public Builder withId(byte[] id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withMembers(List<String> members) {
      this.members = members;
      return this;
    }

    public Builder withAvatar(SignalServiceAttachment avatar) {
      this.avatar = avatar;
      return this;
    }

    //
    public Builder withFenceCommand(SignalServiceProtos.FenceCommand fenceCommand) {
      this.fenceCommand = fenceCommand;
      return this;
    }

    public SignalServiceGroup build() {
      if (id == null) throw new IllegalArgumentException("No group ID specified!");

      if (type == Type.UPDATE && name == null && members == null && avatar == null) {
        throw new IllegalArgumentException("Group update with no updates!");
      }

      return new SignalServiceGroup(type, id, name, members, avatar, fenceCommand);//  fencecommand
    }

  }

}
