package com.unfacd.android.mapable;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

public class GroupAvatarHelper {

  private static final String AVATAR_DIRECTORY = "gavatars";

  public static InputStream getInputStreamFor(@NonNull Context context, @NonNull String filePrefix)
          throws IOException
  {
    return new FileInputStream(getGroupAvatarFile(context, filePrefix));
  }

  public static List<File> getGroupAvatarFiles(@NonNull Context context) {
    File   avatarDirectory = new File(context.getFilesDir(), AVATAR_DIRECTORY);
    File[] results         = avatarDirectory.listFiles();

    if (results == null) return new LinkedList<>();
    else                 return Stream.of(results).toList();
  }

  public static void delete(@NonNull Context context, @NonNull String filePrefix) {
    getGroupAvatarFile(context, filePrefix).delete();
  }

  public static @NonNull File getGroupAvatarFile(@NonNull Context context, @NonNull String filePrefix) {
    File avatarDirectory = new File(context.getFilesDir(), AVATAR_DIRECTORY);
    avatarDirectory.mkdirs();

    return new File(avatarDirectory, new File(filePrefix).getName());
  }

  public static void setGroupAvatar(@NonNull Context context, @NonNull String filePrefix, @Nullable byte[] data)
          throws IOException
  {
    if (data == null)  {
      delete(context, filePrefix);
    } else {
      FileOutputStream out = new FileOutputStream(getGroupAvatarFile(context, filePrefix));
      out.write(data);
      out.close();
    }
  }

}