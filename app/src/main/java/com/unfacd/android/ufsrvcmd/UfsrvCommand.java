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

import com.google.common.hash.Hashing;
import com.unfacd.android.ApplicationContext;

import org.signal.core.util.logging.Log;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.LocationCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.StateCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;

public class UfsrvCommand implements Serializable
{
  private static final String TAG = Log.tag(UfsrvCommand.class);

  private boolean isIntegritySensitive = false;
  private  int command;
  private String serverCommandPath;
  private String serverCommandPathArgs;
  private TransportType transportType = TransportType.API_SERVICE;

  private FenceCommand    fence;
  private MessageCommand  message;
  private UserCommand     user;
  private CallCommand     call;
  private ReceiptCommand  receipt;
  private SyncCommand     sync;
  private LocationCommand location;
  private StateCommand    state;

  //this is the final encapsulating envelope for ufsrv commands
  private UfsrvCommandWire ufsrvWireCommand;

  transient private UfsrvCommandWire.Builder ufsrvCommandWireBuilder = null;

  transient private FenceCommand.Builder      fenceCommandBuilder = null;
  transient private MessageCommand.Builder    messageCommandBuilder = null;
  transient private UserCommand.Builder       userCommandBuilder = null;
  transient private CallCommand.Builder       callCommandBuilder = null;
  transient private ReceiptCommand.Builder    receiptCommandBuilder = null;
  transient private SyncCommand.Builder       syncCommandBuilder = null;
  transient private LocationCommand.Builder   locationCommandBuilder = null;
  transient private StateCommand.Builder      stateCommandBuilder = null;

  //whether the containing UfsrvCommand has been built or not
  private boolean isBuilt = false;

  //whether the sub command contained inside UfsrvCommand has been built or not
  private boolean isTypeBuilt = false;

  private boolean e2ee = true;//end to end encryption option

  public enum Type {
    //AA shouldmatch the order as defined in proto
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

  private Type commandType = Type.UNKNOWN;

  public enum TransportType {
    UNDEFINED(0),
    LOCAL_PIPE(1), //websocket
    API_SERVICE(2);

    private int value;

    TransportType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  //DONT USE THIS JUST YET AS IT DOESNT BUILD OBJECT CORRECTLY use constructor with command eg public UfsrvCommand (FenceCommand fenceCommand, boolean e2ee)
  public UfsrvCommand(UfsrvCommandWire ufsrvCommandWire, boolean isCommandBuilt, boolean isTypeBuilt)
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

  /*public <T extends GeneratedMessage>  UfsrvCommand (Class<T> kommand)
  {
    setCommand(kommand);
  }

  public <T extends GeneratedMessage> UfsrvCommand (Class<T> kommand, boolean e2ee)
  {
    this(kommand);
    this.e2ee = e2ee;
  }

  public <T extends GeneratedMessage> UfsrvCommand (Class<T> kommand, boolean e2ee, TransportType transportType)
  {
    this(kommand, e2ee);
    this.transportType = transportType;
  }

  public <T extends GeneratedMessage.Builder> UfsrvCommand (Class<T> commandBuilder, boolean e2ee)
  {
    initialiseUfsrvCommandBuilder(commandBuilder, e2ee);
  }

  private <T extends GeneratedMessage> void setCommand (Class<T> kommand)
  {
    setCommand(kommand);
  }
*/

  private <T> void initialiseUfsrvCommandBuilder(Class<T> kommandBuilder, boolean e2ee)
  {
    initialiseUfsrvCommandBuilder(kommandBuilder, e2ee);
  }

  public boolean isIntegritySensitive()
  {
    return isIntegritySensitive;
  }

  public Optional<String> getRequestHashForIntegrityToken()
  {
    if (!isBuilt) {
      Log.w(TAG, "Command not built yet");
      return Optional.empty();
    }

    String hashInputString = TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance()) + ":" + TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance());
    String requestHash = Hashing.sha256()
                                .hashString(hashInputString, StandardCharsets.UTF_8)
                                .toString();
    return Optional.of(requestHash);
  }

  public boolean isBuilt()
  {
    return isBuilt;
  }

  public boolean isTypeBuilt()
  {
    return isTypeBuilt;
  }

  public  UfsrvCommandWire.Builder getUfsrvCommandWireBuilder()
  {
    return this.ufsrvCommandWireBuilder;
  }

  public TransportType getTransportType()
  {
    return transportType;
  }

  public void setTransportType(TransportType transportType)
  {
    this.transportType = transportType;
  }

  //Type must have been previously set
  public UfsrvCommand build()
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
  public UfsrvCommandWire buildIfNecessary()
  {
    if (isBuilt == false) {
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
        case LOCATION:
          buildLocationCommandIfNecessary();
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

  String serialise()
  {
    if (isBuilt) {
      String serialised = Base64.encodeBytes(ufsrvWireCommand.toByteArray());
     // Log.d("UfsrvCommand", "serialise: Serialised to '"+serialised+"'");
      return serialised;
    }

    Log.e(TAG, "serialise: UfsrvCommand was not built");

    return "";
  }

  //whole object, not just protobuf
  public String serialiseThis()
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
        Log.e(TAG, x.getMessage());
        return "";
      }
    } else {
      Log.e(TAG, "serialiseThis: UfsrvCommand was not built");

      return "";
    }
  }

  static public UfsrvCommand deserialiseThis(String ufsrvCommandEncoded)
  {
    if (!TextUtils.isEmpty(ufsrvCommandEncoded)) {
      try {
        ByteArrayInputStream bis = new ByteArrayInputStream(Base64.decode(ufsrvCommandEncoded));
        ObjectInput in = new ObjectInputStream(bis);
        return ((UfsrvCommand) in.readObject());
      } catch (IOException|ClassNotFoundException x) {
        Log.e(TAG, x.getMessage());
      }
    }

    return null;
  }

  @Override
  public String toString() {
    return buildToSerialise();
  }

  public UfsrvCommand includeAttachments( List<SignalServiceProtos.AttachmentPointer> pointers)
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

  /// START FenceCommand \\\

  /**
   * Constructor with final built FenceCommand
   * @param fenceCommand a built FenceCommand
   */
  public UfsrvCommand(FenceCommand fenceCommand)
  {
    setCommand(fenceCommand);
  }

  public UfsrvCommand(FenceCommand fenceCommand, boolean e2ee, boolean isIntegritySensitive)
  {
    this(fenceCommand);
    this.e2ee = e2ee;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  public UfsrvCommand(FenceCommand fenceCommand, boolean e2ee, TransportType transportType, boolean isIntegritySensitive)
  {
    this(fenceCommand, e2ee, false);
    this.transportType = transportType;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  /**
   *  constructor with builder. Climbs the chain at the lowest level
   * @param fenceCommandBuilder ence command in transient state. The user is expected to finalise the instance (build it) before inv
   */
  private void initialiseUfsrvCommandBuilder(FenceCommand.Builder fenceCommandBuilder, boolean e2ee)
  {
    this.fenceCommandBuilder  = fenceCommandBuilder;
    commandType               = Type.FENCE;
    this.isTypeBuilt          = false;
    this.e2ee                 = e2ee;
  }

  public void setUfsrvCommandWireBuilder(FenceCommand fenceCommand)
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

 //  //plumb in the (already built) fencecommand into the containing ufsrvcommand
//  //final containing UfsrvCommand is still in unbuilt state
  private void setCommand(FenceCommand fenceCommand)
  {
    this.command = fenceCommand.getHeader().getCommand();
    commandType = Type.FENCE;
    this.fence = fenceCommand;
    this.serverCommandPath = PushServiceSocket.getUfsrvFenceCommand();
    serverCommandPathArgs = String.format(Locale.getDefault(), "%s", serverCommandPath);

    setUfsrvCommandWireBuilder (fenceCommand);

    this.isTypeBuilt = true;
  }

  private void includeAttachmentsforFenceCommand(List<SignalServiceProtos.AttachmentPointer> pointers)
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
        if (pointer.hasCaption()) attachmentRecord.setCaption(pointer.getCaption());
        if (pointer.hasBlurHash())  attachmentRecord.setBlurHash(pointer.getBlurHash());

        attachmentRecords.add(attachmentRecord.build());
      }

      fenceCommandBuilder.addAllAttachments(attachmentRecords);
    } else {
      Log.d(TAG, "FenceCommand Type already built...");
    }

  }

  /**
   * Command finaliser. Checks the state of the command and build subtype where necessary.
   * Constructor must havebeen invoked with builder type previously
   * @return file immutable UfsrvCommand
   */
  private UfsrvCommand buildFenceCommandIfNecessary()
  {
    if (fenceCommandBuilder == null && fence == null) throw new AssertionError("Builder or Command object is null");

    if (fenceCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.fence = fenceCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(this.fence);
      }

      return this.build();
    }//the fence already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built

  }

  public UfsrvCommand buildFromFenceCommand()
  {
    return buildFenceCommandIfNecessary();

  }

  public String buildToSerialiseFromFenceCommand()
  {
    return buildFenceCommandIfNecessary().serialise();
  }

  public FenceCommand getFence()
  {
    return fence;
  }

  public FenceCommand.Builder getFenceCommandBuilder()
  {
    return fenceCommandBuilder;
  }
  /// END Type FenceCommand \\\

  /// START Type MessageCommand \\\
  public UfsrvCommand(MessageCommand messageCommand, boolean e2ee, boolean isIntegritySensitive)
  {
    setMessageCommand(messageCommand);
    this.e2ee =  e2ee;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  public UfsrvCommand(MessageCommand.Builder messageCommandBuilder)
  {
    this.messageCommandBuilder  = messageCommandBuilder;
    commandType                 = Type.MESSAGE;
    this.isTypeBuilt            = false;
  }

  public UfsrvCommand(MessageCommand.Builder messageCommandBuilder, boolean e2ee, TransportType transportType, boolean isIntegritySensitive)
  {
   this(messageCommandBuilder);
    this.e2ee                   = e2ee;
    this.transportType          = transportType;
    this.isIntegritySensitive   = isIntegritySensitive;
  }

  private void includeAttachmentsforMessageCommand(List<SignalServiceProtos.AttachmentPointer> pointers)
  {
    if (!isTypeBuilt) {
      //todo: user UfsrvMessageUtils.adaptAttachmentRecords()
      List<SignalServiceProtos.AttachmentRecord> attachmentRecords = new LinkedList<>();

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
        if (pointer.hasCaption()) attachmentRecord.setCaption(pointer.getCaption());
        if (pointer.hasBlurHash())  attachmentRecord.setBlurHash(pointer.getBlurHash());

        attachmentRecords.add(attachmentRecord.build());
      }

      messageCommandBuilder.addAllAttachments(attachmentRecords);
    }
    else
      Log.d(TAG, "MessageCommand Type already built...");

  }

  public void setUfsrvCommandWireBuilder(MessageCommand messageCommand)
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
  private void setMessageCommand(MessageCommand messageCommand)
  {
    this.command  = messageCommand.getHeader().getCommand();
    commandType   = Type.MESSAGE;
    this.message  = messageCommand;

    this.serverCommandPath=PushServiceSocket.getUfsrvMessageCommand();
    serverCommandPathArgs = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (messageCommand);

    this.isTypeBuilt  = true;
    this.isBuilt      = false;
    //IMPORTANT: we do this outside the context of this methid, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  private void setCommand (MessageCommand messageCommand)
  {
    setMessageCommand (messageCommand);
  }

  private UfsrvCommand buildMessageCommandIfNecessary()
  {
    if (messageCommandBuilder == null && message == null) throw new AssertionError("Builder or Comand object is null");

    if (messageCommandBuilder != null) {
      if (isTypeBuilt == false) {
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

  public MessageCommand getMessage()
  {
    return message;
  }

  public MessageCommand.Builder getMessageCommandBuilder()
  {
    return messageCommandBuilder;
  }
  /// End MessageCommand

  //START UserCommand
//  /**
//   * Constructor with final built UserCommand
//   * @param userCommand a built UserCommand
//   */
  public UfsrvCommand(UserCommand userCommand)
  {
    setCommand(userCommand);
  }

  public UfsrvCommand(UserCommand userCommand, boolean e2ee)
  {
    this(userCommand);
    this.e2ee = e2ee;
  }

  public void setUfsrvCommandWireBuilder(UserCommand userCommand)
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
  private void setCommand(UserCommand userCommand) {
    this.command      = userCommand.getHeader().getCommand();
    this.commandType  = Type.USER;
    this.user         = userCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvUserCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (userCommand);

    this.isTypeBuilt = true;
    this.isBuilt = false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  public UfsrvCommand(UserCommand.Builder userCommandBuilder, boolean e2ee, boolean isIntegritySensitive)
  {
    this.userCommandBuilder = userCommandBuilder;
    commandType             = Type.USER;
    this.isTypeBuilt        = false;
    this.e2ee               = e2ee;
    this.isIntegritySensitive = false;
  }

  public UfsrvCommand(UserCommand.Builder userCommandBuilder, boolean e2ee, TransportType transportType, boolean isIntegritySensitive)
  {
    this(userCommandBuilder, e2ee, false);
    this.transportType = transportType;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  private UfsrvCommand buildUserCommandIfNecessary()
  {
    if (userCommandBuilder == null && user == null) throw new AssertionError("Builder or Command object is null");

    if (userCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.user = userCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(user);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built

  }

  private void includeAttachmentsforUserCommand(List<SignalServiceProtos.AttachmentPointer> pointers)
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
        if (pointer.hasCaption()) attachmentRecord.setCaption(pointer.getCaption());
        if (pointer.hasBlurHash())  attachmentRecord.setBlurHash(pointer.getBlurHash());

        attachmentRecords.add(attachmentRecord.build());
      }

      userCommandBuilder.addAllAttachments(attachmentRecords);
    } else Log.d(TAG, "UserCommand Type already built...");

  }

  public UserCommand.Builder getUserCommandBuilder()
  {
    return userCommandBuilder;
  }

  public UserCommand getUser()
  {
    return user;
  }
  //END UserCommand

  //START CallCommand
  //TBD
//  /**
//   * Constructor with final built UserCommand
//   * @param callCommand a built UserCommand
//   */
  public UfsrvCommand(CallCommand callCommand)
  {
    setCommand(callCommand);
  }

  public UfsrvCommand(CallCommand callCommand, boolean e2ee)
  {
    this(callCommand);
    this.e2ee = e2ee;
  }

  public UfsrvCommand(CallCommand.Builder callCommandBuilder, boolean e2ee)
  {
    this.callCommandBuilder = callCommandBuilder;
    commandType             = Type.CALL;
    this.isTypeBuilt        = false;
    this.e2ee               = e2ee;
  }

  public UfsrvCommand(CallCommand.Builder callCommandBuilder, boolean e2ee, TransportType transportType, boolean isIntegritySensitive)
  {
    this(callCommandBuilder, e2ee);
    this.transportType = transportType;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  public void setUfsrvCommandWireBuilder(CallCommand callCommand)
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

   private void setCommand(CallCommand callCommand)
  {
    this.command      = callCommand.getHeader().getCommand();
    this.commandType  = Type.CALL;
    this.call         = callCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvCallCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder(callCommand);

    this.isTypeBuilt  = true;
    this.isBuilt      = false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  private UfsrvCommand buildCallCommandIfNecessary()
  {
    if (callCommandBuilder == null && call == null) throw new AssertionError("Builder or Command object is null");

    if (callCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.call = callCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(call);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public CallCommand.Builder getCallCommandBuilder()
  {
    return callCommandBuilder;
  }

  public CallCommand getCall()
  {
    return call;
  }
  //END CallCommand

  //START ReceiptCommand
  public UfsrvCommand(ReceiptCommand receiptCommand, boolean e2ee, boolean isIntegritySensitive)
  {
    setCommand(receiptCommand);
    this.e2ee = e2ee;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  public UfsrvCommand(ReceiptCommand receiptCommand, boolean e2ee, TransportType transportType)
  {
    this(receiptCommand, e2ee, false);
    this.transportType = transportType;
  }

  public void setUfsrvCommandWireBuilder(ReceiptCommand receiptCommand)
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

  private void setCommand(ReceiptCommand receiptCommand)
  {
    this.command      = receiptCommand.getHeader().getCommand();
    this.commandType  = Type.RECEIPT;
    this.receipt      = receiptCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvReceiptCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (receiptCommand);

    this.isTypeBuilt  = true;
    this.isBuilt      = false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  private UfsrvCommand buildReceiptCommandIfNecessary ()
  {
    if (receiptCommandBuilder == null && receipt == null) return null;

    if (receiptCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.receipt = receiptCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(this.receipt);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public ReceiptCommand.Builder getReceiptCommandBuilder()
  {
    return receiptCommandBuilder;
  }

  public ReceiptCommand getReceipt()
  {
    return receipt;
  }
  //END ReceiptCommand

  //START LocationCommand
  public UfsrvCommand(LocationCommand locationCommand, boolean e2ee)
  {
    setCommand(locationCommand);
    this.e2ee = e2ee;
  }

  public UfsrvCommand(LocationCommand locationCommand, boolean e2ee, TransportType transportType)
  {
    this(locationCommand, e2ee);
    this.transportType = transportType;
  }

  public void setUfsrvCommandWireBuilder(LocationCommand locationCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(locationCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvLocationCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_LOCATION);
      this.ufsrvCommandWireBuilder.setLocationCommand(locationCommand);
    }
  }

  private void setCommand(LocationCommand locationCommand)
  {
    this.command      = locationCommand.getHeader().getCommand();
    this.commandType  = Type.LOCATION;
    this.location     = locationCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvLocationCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (locationCommand);

    this.isTypeBuilt  = true;
    this.isBuilt      = false;
  }

  private UfsrvCommand buildLocationCommandIfNecessary()
  {
    if (locationCommandBuilder == null && location == null) return null;

    if (locationCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.location = locationCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(this.location);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public LocationCommand.Builder getLocationCommandBuilder()
  {
    return locationCommandBuilder;
  }

  public LocationCommand getLocation()
  {
    return location;
  }
  //END LocationCommand

  //START StateCommand
  public UfsrvCommand(StateCommand stateCommand, boolean e2ee)
  {
    setCommand(stateCommand);
    this.e2ee = e2ee;
  }

  public UfsrvCommand(StateCommand stateCommand, boolean e2ee, TransportType transportType)
  {
    this(stateCommand, e2ee);
    this.transportType = transportType;
  }

  public void setUfsrvCommandWireBuilder(StateCommand stateCommand)
  {
    if (this.ufsrvCommandWireBuilder == null) {
      this.ufsrvCommandWireBuilder                  = UfsrvCommandWire.newBuilder();
      CommandHeader.Builder commandHeaderBuilder    = CommandHeader.newBuilder();

      //build/link header
      commandHeaderBuilder.setCommand(stateCommand.getHeader().getCommand());
      commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvStateCommand());
      this.ufsrvCommandWireBuilder.setHeader(commandHeaderBuilder.build());

      this.ufsrvCommandWireBuilder.setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_STATE);
      this.ufsrvCommandWireBuilder.setStateCommand(stateCommand);
    }
  }

  private void setCommand(StateCommand stateCommand)
  {
    this.command      = stateCommand.getHeader().getCommand();
    this.commandType  = Type.RECEIPT;
    this.state      = stateCommand;

    this.serverCommandPath      = PushServiceSocket.getUfsrvStateCommand();
    this.serverCommandPathArgs  = String.format("%s", serverCommandPath);

    setUfsrvCommandWireBuilder (stateCommand);

    this.isTypeBuilt  = true;
    this.isBuilt      = false;
    //IMPORTANT: we do this outside the context of this method, just before the packet is finalised (in createMessageContent()) for wire transmission
    //this.ufsrvWireCommand=ufsrvCommandBuilder.build();
  }

  private UfsrvCommand buildStateCommandIfNecessary()
  {
    if (stateCommandBuilder == null && state == null) return null;

    if (stateCommandBuilder != null) {
      if (isTypeBuilt == false) {
        this.state = stateCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(this.state);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public StateCommand.Builder getStateCommandBuilder()
  {
    return stateCommandBuilder;
  }

  public StateCommand getState()
  {
    return state;
  }
  //END StateCommand

  //START SyncCommand
//  /**
//   * Constructor with final built SyncCommand
//   * @param syncCommand a built SyncCommand
//   */
  public UfsrvCommand(SyncCommand syncCommand)
  {
    setCommand(syncCommand);
  }

  //plumb in the (already built) MessageCommand into the containing ufsrvWireCommand
  //final containing UfsrvCommand is still in unbuilt state
  private void setCommand(SyncCommand syncCommand)
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

//  public UfsrvCommand (SyncCommand syncCommand, boolean e2ee)
//  {
//    this(syncCommand);
//    this.e2ee=e2ee;
//  }

  private UfsrvCommand buildSyncCommandIfNecessary()
  {
    if (syncCommandBuilder==null && sync==null) return null;

    if (syncCommandBuilder!=null) {
      if (isTypeBuilt==false) {
        this.sync = syncCommandBuilder.build();
        isTypeBuilt = true;

        setCommand(this.sync);
      }

      return this.build();
    }//the message already set and built so we just build the containing UfsrvCommand
    else
    if(!isBuilt)  return this.build();
    else return this; //already built
  }

  public SyncCommand.Builder getSyncCommandBuilder()
  {
    return syncCommandBuilder;
  }

  public SyncCommand getSync()
  {
    return sync;
  }

  //END SyncCommand

  public int getCommand()
  {
    return command;
  }

  public String getServerCommandPath()
  {
    return this.serverCommandPath;
  }

  public String getServerCommandPathArgs()
  {
    return serverCommandPathArgs;
  }

  public boolean isE2ee()
  {
    return e2ee;
  }

  public Type getCommandType()
  {
    return commandType;
  }
  //END SyncCommand
}
