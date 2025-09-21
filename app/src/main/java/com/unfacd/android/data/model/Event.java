package com.unfacd.android.data.model;

import android.text.Spanned;

import com.fasterxml.jackson.databind.JsonNode;

import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.util.TimerTask;

/*@Table(databaseName = UfDatabase.NAME,
        uniqueColumnGroups = {@UniqueGroup(groupNumber = 1, uniqueConflict = ConflictAction.REPLACE)})*/
public class Event /*extends ObservableBaseModel*/
{
//  //@Column
 // @PrimaryKey
 // @Unique(unique = false, uniqueGroups = 1)
  public int cid;

  //AA+
  public Object obj;
  public WebSocketProtos.WebSocketMessage wsm;

  ////@Column
  //@PrimaryKey
  //@Unique(unique = false, uniqueGroups = 1)
  public long bid;

  ////@Column
  //@PrimaryKey
  //@Unique(unique = false, uniqueGroups = 1)
  public long eid;

  ////@Column
  public String type;

  ////@Column
  public String msg;

  //@Column
  public String hostmask;

  //@Column(name = "event_from")
  public String from;

  //@Column
  public String from_mode;

  //@Column
  public String nick;

  //@Column
  public String old_nick;

  //@Column
  public String server;

  //@Column
  public String diff;

  //@Column
  public String chan;

  //@Column
  public boolean highlight;

  //@Column
  public boolean self;

  //@Column
  public boolean to_chan;

  //@Column
  public boolean to_buffer;

  //@Column
  public int color;

  //@Column
  public int bg_color;

  //@Column
  public JsonNode ops;

  //@Column
  public long group_eid;

  //@Column
  public int row_type = 0;

  //@Column
  public String group_msg;

  //@Column
  public boolean linkify = true;

  //@Column
  public String target_mode;

  //@Column
  public int reqid;

  //@Column
  public boolean pending;

  //@Column
  public boolean failed;

  //@Column
  public String command;

  //@Column
  public int day = -1;

  //@Column
  public String contentDescription;

  //@Column
  public JsonNode entities;

  public String timestamp;
  public String html;
  public Spanned formatted;
  public TimerTask expiration_timer;

  public String toString ()
  {
    return "{" +
            "cid: " + cid +
            " bid: " + bid +
            " eid: " + eid +
            " type: " + type +
            " timestamp: " + timestamp +
            " from: " + from +
            " msg: " + msg +
            " html: " + html +
            " group_eid: " + group_eid +
            " group_msg: " + group_msg +
            " pending: " + pending +
            "}";
  }

}
