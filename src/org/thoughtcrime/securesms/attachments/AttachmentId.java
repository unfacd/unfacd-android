package org.thoughtcrime.securesms.attachments;

import com.fasterxml.jackson.annotation.JsonProperty;
import android.text.TextUtils;

import org.thoughtcrime.securesms.util.Util;

public class AttachmentId {

  @JsonProperty
  private final long rowId;

  @JsonProperty
  private final long uniqueId;
  //
  @JsonProperty
  private final String ufId;

//
  public AttachmentId(@JsonProperty("rowId") long rowId,  @JsonProperty("uniqueId") long uniqueId,  @JsonProperty("ufId") String ufId) {
    this.rowId    = rowId;
    this.uniqueId = uniqueId;

    if (TextUtils.isEmpty(ufId))  this.ufId="0";
    else                          this.ufId=ufId;
  }

  public long getRowId() {
    return rowId;
  }

  public long getUniqueId() {
    return uniqueId;
  }

  public String[] toStrings() {
    return new String[] {String.valueOf(rowId), String.valueOf(uniqueId), ufId};// ufId
  }

  public String toString() {
    return "(row id: " + rowId + ", unique ID: " + uniqueId + ", ufId: "+ufId+")";
  }

  public boolean isValid() {
    return rowId >= 0 && uniqueId >= 0 && !TextUtils.isEmpty(ufId);// ufid
  }

  public String getUfId ()
  {
    return ufId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    AttachmentId attachmentId = (AttachmentId)o;

    if (rowId != attachmentId.rowId) return false;
    return uniqueId == attachmentId.uniqueId;
  }

  @Override
  public int hashCode() {
    return Util.hashCode(rowId, uniqueId);
  }
}
