package org.signal.core.util;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;

public final class ListUtil {
  private ListUtil() {}

  public static <E> List<List<E>> chunk(@NonNull List<E> list, int chunkSize) {
    List<List<E>> chunks = new ArrayList<>(list.size() / chunkSize);

    for (int i = 0; i < list.size(); i += chunkSize) {
      List<E> chunk = list.subList(i, Math.min(list.size(), i + chunkSize));
      chunks.add(chunk);
    }

    return chunks;
  }
}