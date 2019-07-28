package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.List;

/**
 *
 */

public class JsonEntityFencesNearBy
{
  @JsonProperty
  private double  longitude;

  @JsonProperty
  private  double latitude;

  @JsonProperty
  private  int  radius;

  @JsonProperty
  private int count;

  //resppppp
  @JsonProperty
  private int success;

  @JsonProperty
  String payload;

//  @JsonProperty
//  private List<SignalServiceProtos.FenceRecord> fenceRecords;//for protobuf based response
//
//  @JsonProperty
//  private SignalServiceProtos.LocationRecord locationRecord;//for protobuf based response

  public JsonEntityFencesNearBy ()
  {
  }

  //used for initiating outgoing request
  public JsonEntityFencesNearBy (double longitude, double latitude, int radius, int count)
  {
    this.longitude  = longitude;
    this.latitude   = latitude;
    this.count      = count;
    this.radius     = radius;
  }

  public int getSuccess ()
  {
    return success;
  }

  public String getPayload()  {return payload;}

//  public List<SignalServiceProtos.FenceRecord> getFenceRecords ()
//  {
//    return fenceRecords;
//  }
//
//  public SignalServiceProtos.LocationRecord getLocationRecord ()
//  {
//    return locationRecord;
//  }
}
