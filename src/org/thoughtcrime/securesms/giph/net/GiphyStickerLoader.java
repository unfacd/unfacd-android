package org.thoughtcrime.securesms.giph.net;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class GiphyStickerLoader extends GiphyLoader {

  public GiphyStickerLoader(@NonNull Context context, @Nullable String searchString) {
    super(context, searchString);
  }

  @Override
  protected String getTrendingUrl() {
    //return "https://api.giphy.com/v1/stickers/trending?api_key=3o6ZsYH6U6Eri53TXy&offset=%d&limit=" + PAGE_SIZE;
    return "https://api.giphy.com/v1/stickers/trending?api_key=miDF9xez7bY3ljCXQ2HcIRaPMSIGJxgt&offset=%d&limit=" + PAGE_SIZE;
  }

  @Override
  protected String getSearchUrl() {
    //return "https://api.giphy.com/v1/stickers/search?q=cat&api_key=3o6ZsYH6U6Eri53TXy&offset=%d&limit=" + PAGE_SIZE + "&q=%s";
    return "https://api.giphy.com/v1/stickers/search?q=cat&api_key=miDF9xez7bY3ljCXQ2HcIRaPMSIGJxgt&offset=%d&limit=" + PAGE_SIZE + "&q=%s";
  }
}
