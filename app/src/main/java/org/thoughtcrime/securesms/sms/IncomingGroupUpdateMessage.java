package org.thoughtcrime.securesms.sms;

import com.google.protobuf.ByteString;
import com.unfacd.android.utils.UfsrvFenceUtils;

import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.mms.MessageGroupContext;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.util.Optional;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

public class IncomingGroupUpdateMessage extends IncomingTextMessage {

  private final MessageGroupContext groupContext;

  public IncomingGroupUpdateMessage(IncomingTextMessage base, GroupContext groupContext, String body) {
    this(base, new MessageGroupContext(groupContext));
  }

  public IncomingGroupUpdateMessage(IncomingTextMessage base, DecryptedGroupV2Context groupV2Context) {
    this(base, new MessageGroupContext(groupV2Context));
  }

  public IncomingGroupUpdateMessage(IncomingTextMessage base, MessageGroupContext groupContext) {
    super(base, groupContext.getEncodedGroupContext());
    this.groupContext = groupContext;
  }

  //AA+  ufsrvcommand. base will most likely contain reference to the same ufsrvcommand
  public IncomingGroupUpdateMessage(IncomingTextMessage base, GroupContext groupContext, String body, UfsrvCommandWire ufsrvCommandWire) {
    super(base, body, ufsrvCommandWire);
    this.groupContext = new MessageGroupContext(groupContext);

  }
  //

  @Override
  public boolean isGroup() {
    return true;
  }

  public boolean isUpdate() {
    return GroupV2UpdateMessageUtil.isUpdate(groupContext) || groupContext.requireGroupV1Properties().isUpdate();
  }

  public boolean isGroupV2() {
    return GroupV2UpdateMessageUtil.isGroupV2(groupContext);
  }

  public boolean isQuit() {
    return !isGroupV2() && groupContext.requireGroupV1Properties().isQuit();
  }

  @Override
  public boolean isJustAGroupLeave() {
//    return GroupV2UpdateMessageUtil.isJustAGroupLeave(groupContext);
    return (getUfsrvCommand().getFenceCommand() != null && UfsrvFenceUtils.isFenceCommandLeave(getUfsrvCommand().getFenceCommand()));//AA+
  }

  public boolean isCancelJoinRequest() {
    return GroupV2UpdateMessageUtil.isJoinRequestCancel(groupContext);
  }

  public int getChangeRevision() {
    return GroupV2UpdateMessageUtil.getChangeRevision(groupContext);
  }

  public Optional<ByteString> getChangeEditor() {
    return GroupV2UpdateMessageUtil.getChangeEditor(groupContext);
  }

}
