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

import android.text.TextUtils;

import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ApplicationContext;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncCommand;

public class UfsrvCommand implements Serializable
{
  private  int command;
  private String serverCommandPath;
  private String serverCommandPathArgs;

  private FenceCommand fence;
  private MessageCommand message;
  private UserCommand user;
  private CallCommand call;
  private ReceiptCommand receipt;
  private SyncCommand sync;

  //this is the final encapsulating envelope for ufsrv commands
  private UfsrvCommandWire ufsrvWireCommand;

  transient private UfsrvCommandWire.Builder ufsrvCommandWireBuilder=null;

  transient private FenceCommand.Builder fenceCommandBuilder=null;

  transient private MessageCommand.Builder messageCommandBuilder=null;

  transient private UserCommand.Builder userCommandBuilder=null;

  transient private CallCommand.Builder callCommandBuilder=null;

  transient private ReceiptCommand.Builder receiptCommandBuilder=null;

  transient private SyncCommand.Builder syncCommandBuilder=null;

  //whether the containing UfsrvCommand has been built or not
  private boolean isBuilt=false;

  //whether the sub command contained inside UfsrvCommand has been built or not
  private boolean isTypeBuilt=false;

  private boolean e2ee=true;//end to end encryption option

  public enum Type {
    // shouldmatch the order as defined in proto
    SESSION,
    FENCE,
    MESSAGE,
    LOCATION,
    USER,
    CALL,
    RECEIPT,
    SYNC,
    SYSTEM,
    UNKNOWN
  }

  private Type commandType=Type.UNKNOWN;


  //DONT USE THIS JUST YET AS IT DOESNT BUILD OBJECT CORRECTLY use constructor with command eg public UfsrvCommand (FenceCommand fenceCommand, boolean e2ee)
  public UfsrvCommand (UfsrvCommandWire ufsrvCommandWire, boolean isCommandBuilt, boolean isTypeBuilt)
  {

    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        commandType  = Type.FENCE;
        command      =  ufsrvCommandWire.getFenceCommand().getHeader().getCommand();
        this.ufsrvWireCommand = ufsrvCommandWire;
        fence=ufsrvCommandWire.getFenceCommand();
        break;

      case UFSRV_MESSAGE:
        commandType = Type.MESSAGE;
        command     = ufsrvCommandWire.getMsgCommand().getHeader().getCommand();
        this.ufsrvWireCommand = ufsrvCommandWire;
        message=ufsrvCommandWire.getMsgCommand();
        break;

      case UFSRV_USER:
        commandType = Type.USER;
        command     = ufsrvCommandWire.getUserCommand().getHeader().getCommand();
        this.ufsrvWireCommand = ufsrvCommandWire;
        user=ufsrvCommandWire.getUserCommand();
        break;

      case UFSRV_CALL:
        commandType = Type.CALL;
        command     = ufsrvCommandWire.getCallCommand().getHeader().getCommand();
        this.ufsrvWireCommand = ufsrvCommandWire;
        call=ufsrvCommandWire.getCallCommand();
        break;
    }

    this.isBuilt      = isCommandBuilt;
    this.isTypeBuilt  = isTypeBuilt;
  }

  public boolean isBuilt ()
  {
    return isBuilt;
  }

  public boolean isTypeBuilt ()
  {
    return isTypeBuilt;
  }

  public  UfsrvCommandWire.Builder getUfsrvCommandWireBuilder ()
  {

    return this.ufsrvCommandWireBuilder;
  }

  //Type must have been previously set
  public UfsrvCommand build ()
  {
    if (isBuilt==false && isTypeBuilt==true) {//todo: turn it on
      if (ufsrvCommandWireBuilder==null)  throw new AssertionError("Builder object is null");

      ufsrvWireCommand =ufsrvCommandWireBuilder.build();
      isBuilt=true;

      return this;
    }

    return this;
  }

  //marshals the build sequence across subtypes,before  building final containing UfsrvCommand
  public UfsrvCommandWire buildIfNecessary ()
  {
    if (isBuilt==false) {
      switch (commandType) {
        case FENCE:
          buildFenceCommandIfNecessary();
          break;
        case MESSAGE:
          buildMessageCommandIfNecessary();
          break;
        case USER:
          buildUserCommandIfNecessary();
          break;
        case CALL:
          buildCallCommandIfNecessary();
          break;
        default:
      }

      if (ufsrvCommandWireBuilder != null) {
        ufsrvWireCommand = ufsrvCommandWireBuilder.build();
        isBuilt = true;

        return ufsrvWireCommand;
      }
    }

    return ufsrvWireCommand;
  }

  public String buildToSerialise()
  {
    return (this.build().serialise());
  }

  String serialise ()
  {
    if (isBuilt) {
      String serialised=Base64.encodeBytes(ufsrvWireCommand.toByteArray());
     // Log.d("UfsrvCommand", "serialise: Serialised to '"+serialised+"'");
      return serialised;
    }

    Log.e("UfsrvCommand", "serialise: UfsrvCommand was not built");

    return "";
  }

  //whole object, not just protobuf
  public String serialiseThis ()
  {
    if (true/*isBuilt*/) {
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeObject(this);
        byte b[] = bos.toByteArray();
        out.close();
        bos.close();

        return Base64.encodeBytes(b);
      } catch (IOException x) {
        Log.e("UfsrvCommand", x.getMessage());
        return "";
      }
    } else {
      Log.e("UfsrvCommand", "serialiseThis: UfsrvCommand was not built");

      return "";
    }
  }

  static public UfsrvCommand deserialiseThis (String ufsrvCommandEncoded)
  {
    if (!TextUtils.isEmpty(ufsrvCommandEncoded)) {
      try {
        ByteArrayInputStream bis = new ByteArrayInputStream(Base64.decode(ufsrvCommandEncoded));
        ObjectInput in = new ObjectInputStream(bis);
        return ((UfsrvCommand) in.readObject());
      } catch (IOException|ClassNotFoundException x) {
        Log.e("UfsrvCommand", x.getMessage());
      }
    }

    return null;
  }

  @Override
  public String toString() {
    return buildToSerialise();
  }

  public UfsrvCommand includeAttachments ( List<SignalServiceProtos.AttachmentPointer> pointers)
  {
    switch (commandType)
    {//todo: consolidate ufsrv AttachmentRecord build
      case FENCE:
        includeAttachmentsforFenceCommand(pointers);
        break;
      case MESSAGE:
        includeAttachmentsforMessageCommand(pointers);
        break;
      case USER:
        includeAttachmentsforUserCommand(pointers);
        break;
    }

    return this;
  }

  /// Type FenceCommand \\\

  /**
   * Constructor with final built FenceCommand
   * @param fenceCommand a built FenceCommand
   */
  public UfsrvCommand (FenceCommand fenceCommand)
  {
    setFenceCommand(fenceCommand);
  }

  /**
   *  Constructor with final, built type
   * @param fenceCommand
   * @param e2ee
   */
  public UfsrvCommand (FenceCommand fenceCommand, boolean e2ee)
  {
    this(fenceCommand);
    this.e2ee=e2ee;
  }

  /**
   *  constructor with builder. Climbs the chain at the lowest level
   * @param fenceCommandBuilder ence command in transient state. The user is expected to finalise the instance (build it) before inv
   */
  public UfsrvCommand (FenceCommand.Builder fenceCommandBuilder)
  {
    this.fenceCommandBuilder=fenceCommandBuilder;
    commandType=Type.FENCE;
    this.isTypeBuilt=false;
  }

  private void setFenceCommand ()
  {
    this.setFenceCommand(this.fence);
  }

  public void setUfsrvCommandWireBuilder (FenceCommand fenceCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder  = SignalServiceProtos.UfsrvCommandWire.newBuilder();
      SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder    = SignalServiceProtos.CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(fenceCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      ufsrvCommandWireBuilder.setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      ufsrvCommandWireBuilder.setFenceCommand(fenceCommand);
    }
  }

  //plumb in the (already built) fencecommand into the containing ufsrvcommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setFenceCommand (FenceCommand fenceCommand)
  {
    this.command = fenceCommand.getHeader().getCommand();
    commandType=Type.FENCE;
    this.fence=fenceCommand;
    this.serverCommandPath=PushServiceSocket.getUfsrvFenceCommand();
    serverCommandPathArgs = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (fenceCommand);

    this.isTypeBuilt=true;
  }

  private void includeAttachmentsforFenceCommand ( List<SignalServiceProtos.AttachmentPointer> pointers)
  {
    if (!isTypeBuilt) {
      List<SignalServiceProtos.AttachmentRecord> attachmentRecords=new LinkedList<>();

      for (SignalServiceProtos.AttachmentPointer pointer : pointers) {
        SignalServiceProtos.AttachmentRecord.Builder attachmentRecord=SignalServiceProtos.AttachmentRecord.newBuilder();
        attachmentRecord.setContentType(pointer.getContentType());
        attachmentRecord.setId(pointer.getUfid());
        attachmentRecord.setKey(pointer.getKey());
        attachmentRecord.setDigest(pointer.getDigest());
        attachmentRecord.setSize(pointer.getSize());
        attachmentRecord.setWidth(pointer.getWidth());
        attachmentRecord.setHeight(pointer.getHeight());
        attachmentRecord.setThumbnail(pointer.getThumbnail());
        attachmentRecords.add(attachmentRecord.build());
      }

      fenceCommandBuilder.addAllAttachments(attachmentRecords);
    }
    else
    Log.d("UfsrvCommand", "FenceCommand Type already built...");

  }

  /**
   * Command finaliser. Checks the state of the command and build subtype where necessary.
   * Constructor must havebeen invoked with builder type previously
   * @return file immutable UfsrvCommand
   */
  private UfsrvCommand buildFenceCommandIfNecessary ()
  {
    if (fenceCommandBuilder==null && fence==null) throw new AssertionError("Builder or Command object is null");

    if (fenceCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.fence = fenceCommandBuilder.build();
        isTypeBuilt = true;

        setFenceCommand();
      }

      return this.build();
    }//the fence already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built

  }

  public UfsrvCommand buildFromFenceCommand ()
  {
    return buildFenceCommandIfNecessary();

  }

  public String buildToSerialiseFromFenceCommand ()
  {
    return buildFenceCommandIfNecessary().serialise();
  }

  public FenceCommand getFence ()
  {
    return fence;
  }

  public FenceCommand.Builder getFenceCommandBuilder ()
  {
    return fenceCommandBuilder;
  }

  /// END Type FenceCommand \\\


  /// START Type MessageCommand \\\
  public UfsrvCommand (MessageCommand messageCommand)
  {
    setMessageCommand(messageCommand);
  }

  public UfsrvCommand (MessageCommand.Builder messageCommandBuilder)
  {
    this.messageCommandBuilder  = messageCommandBuilder;
    commandType                 = Type.MESSAGE;
    this.isTypeBuilt            = false;
  }

  public UfsrvCommand (MessageCommand.Builder messageCommandBuilder, boolean e2ee)
  {
    this(messageCommandBuilder);
    this.e2ee = e2ee;
  }

  public UfsrvCommand (MessageCommand messageCommand, boolean e2ee)
  {
    this(messageCommand);
    this.e2ee=e2ee;
  }

  private void includeAttachmentsforMessageCommand ( List<SignalServiceProtos.AttachmentPointer> pointers)
  {
    if (!isTypeBuilt)
    {
      //todo: user UfsrvMessageUtils.adaptAttachmentRecords()
      List<SignalServiceProtos.AttachmentRecord> attachmentRecords=new LinkedList<>();

      for (SignalServiceProtos.AttachmentPointer pointer : pointers) {
        SignalServiceProtos.AttachmentRecord.Builder attachmentRecord=SignalServiceProtos.AttachmentRecord.newBuilder();
        attachmentRecord.setContentType(pointer.getContentType());
        attachmentRecord.setId(pointer.getUfid());
        attachmentRecord.setKey(pointer.getKey());
        attachmentRecord.setDigest(pointer.getDigest());
        attachmentRecord.setSize(pointer.getSize());
        attachmentRecord.setWidth(pointer.getWidth());
        attachmentRecord.setHeight(pointer.getHeight());
        attachmentRecord.setThumbnail(pointer.getThumbnail());
        attachmentRecords.add(attachmentRecord.build());
      }

      messageCommandBuilder.addAllAttachments(attachmentRecords);
    }
    else
      Log.d("UfsrvCommand", "MessageCommand Type already built...");

  }

  public void setUfsrvCommandWireBuilder (MessageCommand messageCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder                = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(messageCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_MESSAGE);
      this.ufsrvCommandWireBuilder.setMsgCommand(messageCommand);
    }
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvcommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setMessageCommand (MessageCommand messageCommand)
  {
    this.command = messageCommand.getHeader().getCommand();
    commandType=Type.MESSAGE;
    this.message=messageCommand;

    this.serverCommandPath=PushServiceSocket.getUfsrvMessageCommand();
    serverCommandPathArgs = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (messageCommand);

    this.isTypeBuilt=true;
    this.isBuilt=false;
    //IMPORTANT: we do this outside the context of this methid, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  private UfsrvCommand buildMessageCommandIfNecessary ()
  {
    if (messageCommandBuilder==null && message==null) throw new AssertionError("Builder or Comand object is null");

    if (messageCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.message = messageCommandBuilder.build();
        isTypeBuilt = true;

        setMessageCommand(message);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public MessageCommand getMessage ()
  {
    return message;
  }

  public MessageCommand.Builder getMessageCommandBuilder ()
  {
    return messageCommandBuilder;
  }

  /// End MessageCommand


  //START UserCommand
  /**
   * Constructor with final built UserCommand
   * @param userCommand a built UserCommand
   */
  public UfsrvCommand (UserCommand userCommand)
  {
    setUserCommand(userCommand);
  }

  public void setUfsrvCommandWireBuilder (UserCommand userCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(userCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvUserCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_USER);
      this.ufsrvCommandWireBuilder.setUserCommand(userCommand);
    }
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvWireCommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setUserCommand (UserCommand userCommand)
  {
    this.command      = userCommand.getHeader().getCommand();
    this.commandType  = Type.USER;
    this.user         = userCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvUserCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (userCommand);
//    this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
//    CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();
//
//    //build/link header
//    commandHeaderBuilder.setCommand(userCommand.getHeader().getCommand());
//    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvUserCommand());
//    this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());
//
//    this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_USER);
//    this.ufsrvCommandWireBuilder.setUserCommand(userCommand);

    this.isTypeBuilt=true;
    this.isBuilt=false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  public UfsrvCommand (UserCommand userCommand, boolean e2ee)
  {
    this(userCommand);
    this.e2ee=e2ee;
  }

  public UfsrvCommand (UserCommand.Builder userCommandBuilder)
  {
    this.userCommandBuilder=userCommandBuilder;
    commandType=Type.USER;
    this.isTypeBuilt=false;
  }

  public UfsrvCommand (UserCommand.Builder userCommandBuilder, boolean e2ee)
  {
    this(userCommandBuilder);
    this.e2ee=e2ee;
  }

  private UfsrvCommand buildUserCommandIfNecessary ()
  {
    if (userCommandBuilder==null && user==null) throw new AssertionError("Builder or Command object is null");

    if (userCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.user = userCommandBuilder.build();
        isTypeBuilt = true;

        setUserCommand(user);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built

  }

  private void includeAttachmentsforUserCommand ( List<SignalServiceProtos.AttachmentPointer> pointers)
  {
    if (!isTypeBuilt) {
      List<SignalServiceProtos.AttachmentRecord> attachmentRecords=new LinkedList<>();

      for (SignalServiceProtos.AttachmentPointer pointer : pointers) {
        SignalServiceProtos.AttachmentRecord.Builder attachmentRecord=SignalServiceProtos.AttachmentRecord.newBuilder();
        attachmentRecord.setContentType(pointer.getContentType());
        attachmentRecord.setId(pointer.getUfid());
        attachmentRecord.setKey(pointer.getKey());
        attachmentRecord.setDigest(pointer.getDigest());
        attachmentRecord.setSize(pointer.getSize());
        attachmentRecord.setWidth(pointer.getWidth());
        attachmentRecord.setHeight(pointer.getHeight());
        attachmentRecord.setThumbnail(pointer.getThumbnail());
        attachmentRecords.add(attachmentRecord.build());
      }

      userCommandBuilder.addAllAttachments(attachmentRecords);
    } else Log.d("UfsrvCommand", "UserCommand Type already built...");

  }

  public UserCommand.Builder getUserCommandBuilder ()
  {
    return userCommandBuilder;
  }

  public UserCommand getUser ()
  {
    return user;
  }

  //END UserCommand


  //START CallCommand
  /**
   * Constructor with final built UserCommand
   * @param callCommand a built UserCommand
   */
  public UfsrvCommand (CallCommand callCommand)
  {
    setCallCommand(callCommand);
  }

  public UfsrvCommand (CallCommand.Builder callCommandBuilder)
  {
    this.callCommandBuilder=callCommandBuilder;
    commandType=Type.CALL;
    this.isTypeBuilt=false;
  }

  public UfsrvCommand (CallCommand.Builder callCommandBuilder, boolean e2ee)
  {
    this(callCommandBuilder);
    this.e2ee=e2ee;
  }

  public void setUfsrvCommandWireBuilder (CallCommand callCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(callCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvCallCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_CALL);
      this.ufsrvCommandWireBuilder.setCallCommand(callCommand);
    }
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvWireCommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setCallCommand (CallCommand callCommand)
  {
    this.command      = callCommand.getHeader().getCommand();
    this.commandType  = Type.CALL;
    this.call         = callCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvCallCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder(callCommand);

    this.isTypeBuilt=true;
    this.isBuilt=false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  public UfsrvCommand (CallCommand callCommand, boolean e2ee)
  {
    this(callCommand);
    this.e2ee=e2ee;
  }

  private UfsrvCommand buildCallCommandIfNecessary ()
  {
    if (callCommandBuilder==null && call==null) throw new AssertionError("Builder or Command object is null");

    if (callCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.call = callCommandBuilder.build();
        isTypeBuilt = true;

        setCallCommand(call);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public CallCommand.Builder getCallCommandBuilder ()
  {
    return callCommandBuilder;
  }

  public CallCommand getCall ()
  {
    return call;
  }

  //END CallCommand

  //START ReceiptCommand
  /**
   * Constructor with final built ReceiptCommand
   * @param receiptCommand a built ReceiptCommand
   */
  public UfsrvCommand (ReceiptCommand receiptCommand)
  {
    setReceiptCommand(receiptCommand);
  }

  public void setUfsrvCommandWireBuilder (ReceiptCommand receiptCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(receiptCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvReceiptCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_RECEIPT);
      this.ufsrvCommandWireBuilder.setReceiptCommand(receiptCommand);
    }
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvWireCommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setReceiptCommand (ReceiptCommand receiptCommand)
  {
    this.command      = receiptCommand.getHeader().getCommand();
    this.commandType  = Type.RECEIPT;
    this.receipt      = receiptCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvReceiptCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (receiptCommand);

    this.isTypeBuilt=true;
    this.isBuilt=false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  public UfsrvCommand (ReceiptCommand receiptCommand, boolean e2ee)
  {
    this(receiptCommand);
    this.e2ee=e2ee;
  }

  private UfsrvCommand buildReceiptCommandIfNecessary ()
  {
    if (receiptCommandBuilder==null && receipt==null) return null;

    if (receiptCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.receipt = receiptCommandBuilder.build();
        isTypeBuilt = true;

        setReceiptCommand(this.receipt);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public ReceiptCommand.Builder getReceiptCommandBuilder ()
  {
    return receiptCommandBuilder;
  }

  public ReceiptCommand getReceipt ()
  {
    return receipt;
  }

  //END ReceiptCommand

  //START SyncCommand
  /**
   * Constructor with final built SyncCommand
   * @param syncCommand a built SyncCommand
   */
  public UfsrvCommand (SyncCommand syncCommand)
  {
    setSyncCommand(syncCommand);
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvWireCommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setSyncCommand (SyncCommand syncCommand)
  {
    this.command      = syncCommand.getHeader().getCommand();
    this.commandType  = Type.SYNC;
    this.sync         = syncCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvSyncCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
    CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();

    //build/link header
    commandHeaderBuilder.setCommand(syncCommand.getHeader().getCommand());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvSyncCommand());
    this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

    this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_SYNC);
    this.ufsrvCommandWireBuilder.setSyncCommand(syncCommand);

    this.isTypeBuilt=true;
    this.isBuilt=false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  public UfsrvCommand (SyncCommand syncCommand, boolean e2ee)
  {
    this(syncCommand);
    this.e2ee=e2ee;
  }

  private UfsrvCommand buildSyncCommandIfNecessary ()
  {
    if (syncCommandBuilder==null && sync==null) return null;

    if (syncCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.sync = syncCommandBuilder.build();
        isTypeBuilt = true;

        setSyncCommand(this.sync);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public SyncCommand.Builder getSyncCommandBuilder ()
  {
    return syncCommandBuilder;
  }

  public SyncCommand getSync ()
  {
    return sync;
  }

  //END SyncCommand

  public int getCommand ()
  {
    return command;
  }

  public String getServerCommandPath()
  {
    return this.serverCommandPath;
  }

  public String getServerCommandPathArgs ()
  {
    return serverCommandPathArgs;
  }

  public boolean isE2ee ()
  {
    return e2ee;
  }

  public Type getCommandType ()
  {
    return commandType;
  }
}
