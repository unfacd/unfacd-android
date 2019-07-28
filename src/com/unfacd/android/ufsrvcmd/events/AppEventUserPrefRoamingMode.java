package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

public class AppEventUserPrefRoamingMode extends UfsrvCommandEvent
{
  private final int roamingMode;
  private final boolean setting; //value as currently set by server: tru false
  private final int result; //accepted or nor rejected
  private final int origCommand; //the command value which was originaly requested, eg set/unset
  private final Long uid;


  public AppEventUserPrefRoamingMode (Long uid, int roamingMode, boolean setting, int result, int origCommand)
  {
    this.uid          = uid;
    this.roamingMode  = roamingMode;
    this.setting      = setting;
    this.result       = result;
    this.origCommand  = origCommand;
  }

  public int getResult ()
  {
    return result;
  }

  public boolean isSetting ()
  {
    return setting;
  }

  public int getOrigCommand ()
  {
    return origCommand;
  }

  public Long getUid ()
  {
    return this.uid;
  }

  public int getRoamingMode ()
  {
    return roamingMode;
  }
}
