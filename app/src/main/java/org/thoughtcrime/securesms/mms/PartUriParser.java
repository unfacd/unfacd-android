package org.thoughtcrime.securesms.mms;

import android.content.ContentUris;
import android.net.Uri;
import org.signal.core.util.logging.Log;

import org.thoughtcrime.securesms.attachments.AttachmentId;

//AA+ support for ufid
public class PartUriParser {
  private static final String TAG = Log.tag(PartUriParser.class);
  private final Uri uri;

  public PartUriParser(Uri uri) {
    this.uri = uri;
  }

  public AttachmentId getPartId() {
    //return new AttachmentId(getEncodedId(), getUniqueId());
    return new AttachmentId(getId(), getUniqueId(), getUfId());
  }

  private long getId() {
    return ContentUris.parseId(uri);
  }

  private long getUniqueId() {
    return Long.parseLong(uri.getPathSegments().get(1));
  }

  //AA+
  private String getUfId() {
    Log.d(TAG, String.format("getUfId: seg(0):'%s', seg(1):'%s', seg(2 ufid):'%s'", uri.getPathSegments().get(0), uri.getPathSegments().get(1), uri.getPathSegments().get(2)));
    return uri.getPathSegments().get(2);
  }
  //

}
