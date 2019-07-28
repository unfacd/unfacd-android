package com.unfacd.android.ufsrvcmd.events;

import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

public class AppEventMessageNotification extends UfsrvCommandEvent
{
  final private Integer msgCount;

  public AppEventMessageNotification (Integer msgCount)
  {
    this.msgCount=msgCount;
  }

  public Integer getMsgCount ()
  {
    return msgCount;
  }
}
