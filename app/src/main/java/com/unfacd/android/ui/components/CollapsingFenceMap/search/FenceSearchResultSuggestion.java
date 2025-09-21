
package com.unfacd.android.ui.components.CollapsingFenceMap.search;
import android.os.Parcel;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;

import com.arlib.floatingsearchview.suggestions.model.SearchSuggestion;
import com.google.android.gms.maps.model.LatLng;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

import static android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE;


public class FenceSearchResultSuggestion implements SearchSuggestion {

  private boolean mIsHistory = false;
  private CharSequence fname_display;
  private LatLng latLng;
  private long fid;
  static private Splitter rawFenceResultSplitter = Splitter.on(':');
  static final int TEXTSCALE_FACTOR = 30;

  public enum ResultTokens {
    NORMALISED_NAME(0),
    FID(1),
    COUNTRY(2),
    ADMINAREA(3),
    LOCALITY(4),
    SELFZONE(5),
    FNAME(6),
    LONGITUDE(7),
    LATITUDE(8);

    private int value;

    ResultTokens(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public FenceSearchResultSuggestion(String suggestion, int textsize) {

    //"Australia:379854490880180245:Australia:New South Wales:Woronora:selfzone:utf8:151.035395:-34.026778"
    Iterable<String> resultTokens = rawFenceResultSplitter.split(suggestion);

    String fname_normalised =Iterables.get(resultTokens, ResultTokens.NORMALISED_NAME.getValue());
    String fname =Iterables.get(resultTokens, ResultTokens.FNAME.getValue());
    String name_qualifier = String.format("(%s, %s)", Iterables.get(resultTokens, ResultTokens.COUNTRY.getValue()), Iterables.get(resultTokens, ResultTokens.ADMINAREA.getValue()));

    if (!TextUtils.isEmpty(fname)) {
      SpannableString span1 = new SpannableString(name_qualifier);
      span1.setSpan(new AbsoluteSizeSpan(textsize-TEXTSCALE_FACTOR), 0, name_qualifier.length(), SPAN_INCLUSIVE_INCLUSIVE);
      fname_display = TextUtils.concat(fname, " ", span1);
    } else {
      SpannableString span1 = new SpannableString(name_qualifier);
      span1.setSpan(new AbsoluteSizeSpan(textsize-TEXTSCALE_FACTOR), 0, name_qualifier.length(), SPAN_INCLUSIVE_INCLUSIVE);
      fname_display = TextUtils.concat(fname_normalised, " ", span1);
    }
    fid=Long.valueOf(Iterables.get(resultTokens, ResultTokens.FID.getValue()));
    latLng=new LatLng(Float.valueOf(Iterables.get(resultTokens, ResultTokens.LATITUDE.getValue())),
                      Float.valueOf(Iterables.get(resultTokens, ResultTokens.LONGITUDE.getValue())));
  }

  public FenceSearchResultSuggestion(Parcel source) {
//    this.mColorName = source.readString();
//    this.mIsHistory = source.readInt() != 0;
  }

  public void setIsHistory(boolean isHistory) {
//    this.mIsHistory = isHistory;
  }

  public boolean getIsHistory() {
//    return this.mIsHistory;
    return false;
  }

  public LatLng getLatLng ()
  {
    return latLng;
  }


  public long getFid ()
  {
    return fid;
  }


  @Override
  public CharSequence getBody() {
    return fname_display;//fname_normalised;

  }

  public static final Creator<FenceSearchResultSuggestion> CREATOR = new Creator<FenceSearchResultSuggestion>() {
    @Override
    public FenceSearchResultSuggestion createFromParcel(Parcel in) {
      return new FenceSearchResultSuggestion(in);
    }

    @Override
    public FenceSearchResultSuggestion[] newArray(int size) {
      return new FenceSearchResultSuggestion[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    //dest.writeString(mColorName);
    dest.writeInt(mIsHistory ? 1 : 0);
  }

}