package com.unfacd.android.utils;

import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nonnull;


public class UfsrvCommandUtils
{
  private static final String TAG = UfsrvCommandUtils.class.getSimpleName();

  public static SignalServiceProtos.CommandHeader getCommandheader (UfsrvCommandWire ufsrvCommandWire)
  {
    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        return ufsrvCommandWire.getFenceCommand().getHeader();
      case UFSRV_MESSAGE:
        return ufsrvCommandWire.getMsgCommand().getHeader();
      case UFSRV_USER:
        return ufsrvCommandWire.getUserCommand().getHeader();
    }

    return null;
  }

  public static UfsrvUid getOriginatorUserId(UfsrvCommandWire ufsrvCommandWire)
  {
    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        if (ufsrvCommandWire.getFenceCommand().hasOriginator()) return new UfsrvUid(ufsrvCommandWire.getFenceCommand().getOriginator().getUfsrvuid().toByteArray());

      case UFSRV_MESSAGE:
        if (ufsrvCommandWire.getMsgCommand().hasOriginator()) return new UfsrvUid(ufsrvCommandWire.getMsgCommand().getOriginator().getUfsrvuid().toByteArray());

      case UFSRV_USER:
        //user commands are always self-originating, self-directed
        if (ufsrvCommandWire.getUserCommand().hasHeader()) return new UfsrvUid(ufsrvCommandWire.getUserCommand().getHeader().getUfsrvuid().toByteArray());
        break;
    }

    return UfsrvUid.undefinedUfsrvUid;//attribute it to the server
  }

  public static List<SignalServiceProtos.AttachmentRecord> getAttachments(UfsrvCommandWire ufsrvCommandWire)
  {
    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        if (ufsrvCommandWire.getFenceCommand().getAttachmentsCount()>0) return ufsrvCommandWire.getMsgCommand().getAttachmentsList();
      case UFSRV_MESSAGE:
        if (ufsrvCommandWire.getMsgCommand().getAttachmentsCount()>0) return ufsrvCommandWire.getMsgCommand().getAttachmentsList();
      case UFSRV_USER:
        break;
    }

    return Collections.emptyList() ;
  }

  public static Address getEncodedGroupId (UfsrvCommandWire ufsrvCommandWire)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    FenceRecord fenceRecord=UfsrvFenceUtils.getTargetFence(ufsrvCommandWire);
    if (fenceRecord==null)  return null;

    Address encodedGroupId= Address.fromSerialized(groupDatabase.getEncodedGroupId(fenceRecord.getFid(), fenceRecord.getCname(), true));

    return encodedGroupId;
  }

  //
  public static List<SignalServiceProtos.UserRecord> recipientsToUserRecordList (List<Recipient> recipient)
  {
    if (recipient!=null) {
      List<Recipient> recipientsList=recipient;
      if (recipientsList.size()>0) {
        List<SignalServiceProtos.UserRecord> userRecordsList=new LinkedList<>();
        for (Recipient r: recipientsList) {
          SignalServiceProtos.UserRecord.Builder userBuilder  = SignalServiceProtos.UserRecord.newBuilder();
          userBuilder.setUsername("*");//todo: phase out username
          userBuilder.setUfsrvuid(ByteString.copyFrom(r.getUfrsvUidRaw()));
          userRecordsList.add(userBuilder.build());
        }

        return userRecordsList;
      }else {
        return null;
      }
    }

    return null;
  }

  public static List<SignalServiceAttachment> getAttachmentsList (UfsrvCommandWire ufsrvCommandWire)
  {
    List<SignalServiceAttachment> attachments      = new LinkedList<>();
    List<SignalServiceProtos.AttachmentRecord> ufsrvAttachments=getAttachments(ufsrvCommandWire);
    if (ufsrvAttachments==null) return attachments;

    for (SignalServiceProtos.AttachmentRecord pointer : ufsrvAttachments) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), 0,//  ufid
                                                          pointer.getContentType(),
                                                          pointer.getKey().toByteArray(),
                                                          pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.absent(),
                                                          pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.absent(),
                                                          pointer.getWidth(),
                                                          pointer.getHeight(),
                                                          pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.absent(),
                                                          pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.absent(),
                                                         false,
                                                          pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.absent()));
    }

    return attachments;

  }

  public static class CommandArgDescriptor {
    private final int command;
    private final int arg;

    public CommandArgDescriptor(int command, int arg) {
      this.command = command;
      this.arg = arg;
    }

    public int getCommand ()
    {
      return command;
    }

    public int getArg ()
    {
      return arg;
    }
  }

}
