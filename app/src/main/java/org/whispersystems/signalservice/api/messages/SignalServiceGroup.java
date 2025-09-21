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

import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import java.util.Optional;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;

import java.util.List;


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
    UFSRV//AA+ corresponds with values in proto
  }

  private final byte[]                               groupId;
  private final Type                                 type;
  private final Optional<String>                     name;
  private final Optional<List<SignalServiceAddress>> members;
  private final Optional<SignalServiceAttachment>    avatar;
  private final GroupMasterKey                       groupMasterKey; //AA+

  //AA+
  private final long                                       fid;
  private final Optional<SignalServiceProtos.FenceCommand> fenceCommand;

  /**
   * Construct a DELIVER group context.
   * @param groupId
   * @param groupMasterKey
   */
  public SignalServiceGroup (byte[] groupId, long fid, GroupMasterKey groupMasterKey) {
    this(Type.DELIVER, groupId, null, null, null, fid, groupMasterKey);//AA++
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
                            List<SignalServiceAddress> members,
                            SignalServiceAttachment avatar,
                            long fid,
                            GroupMasterKey groupMasterKey)//AA+ fid
  {
    this.type    = type;
    this.groupId = groupId;
    this.name    = Optional.ofNullable(name);
    this.members = Optional.ofNullable(members);
    this.avatar  = Optional.ofNullable(avatar);

    //AA+
    this.groupMasterKey = groupMasterKey;
    this.fid            = fid;
    this.fenceCommand   = Optional.empty();
  }

  //AA+ to support the new fencecommand paramater
  /**
   * Construct a group context.
   * @param type The group message type (update, deliver, quit).
   * @param groupId The group ID.
   * @param name The group title.
   * @param members The group membership list.
   * @param avatar The group avatar icon.
   */
  public SignalServiceGroup(Type type, byte[] groupId, String name,
                            List<SignalServiceAddress> members,
                            SignalServiceAttachment avatar,
                            long fid,
                            FenceCommand fenceCommand,
                            GroupMasterKey groupMasterKey)
  {
    this.type    = type;
    this.groupId = groupId;
    this.name    = Optional.ofNullable(name);
    this.members = Optional.ofNullable(members);
    this.avatar  = Optional.ofNullable(avatar);

    //AA+
    this.groupMasterKey = groupMasterKey;
    this.fid            = fid;
    this.fenceCommand   = Optional.ofNullable(fenceCommand);
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

  public Optional<List<SignalServiceAddress>> getMembers() {
    return members;
  }

  public Optional<SignalServiceAttachment> getAvatar() {
    return avatar;
  }

  public GroupMasterKey getGroupMasterKey ()
  {
    return groupMasterKey;
  }

  public static Builder newUpdateBuilder() {
    return new Builder(Type.UPDATE);
  }

  public static Builder newBuilder(Type type) {
    return new Builder(type);
  }

  //AA+
  public Optional<SignalServiceProtos.FenceCommand> getFenceCommand() {
    return fenceCommand;
  }

  public long getFid () {
    return fid;
  }

  public static class Builder {

    private Type                       type;
    private byte[]                     id;
    private String                     name;
    private List<SignalServiceAddress> members;
    private SignalServiceAttachment    avatar;

    private GroupMasterKey             groupMasterKey;
    private long                       fid;
    private FenceCommand               fenceCommand;//AA+

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

    public Builder withMembers(List<SignalServiceAddress> members) {
      this.members = members;
      return this;
    }

    public Builder withAvatar(SignalServiceAttachment avatar) {
      this.avatar = avatar;
      return this;
    }

    //AA+
    public Builder withFenceCommand(SignalServiceProtos.FenceCommand fenceCommand) {
      this.fenceCommand = fenceCommand;
      return this;
    }

    public Builder withFid (long fid) {
      this.fid = fid;
      return this;
    }

    public Builder withGroupMasterKey (GroupMasterKey groupMasterKey) {
      this.groupMasterKey = groupMasterKey;
      return this;
    }

    public SignalServiceGroup build() {
      if (id == null) throw new IllegalArgumentException("No group ID specified!");

      if (type == Type.UPDATE && name == null && members == null && avatar == null) {
        throw new IllegalArgumentException("Group update with no updates!");
      }

      return new SignalServiceGroup(type, id, name, members, avatar, fid, fenceCommand, groupMasterKey);//AA+  fencecommand
    }

  }

}
