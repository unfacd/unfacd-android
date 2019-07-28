package org.thoughtcrime.securesms.database.loaders;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;

import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.util.AbstractCursorLoader;

import java.util.LinkedList;
import java.util.List;

public class ConversationListLoader extends AbstractCursorLoader {
  private static final String TAG = AbstractCursorLoader.class.getSimpleName();
  private final String    filter;
  private final boolean   archived;

  private final GroupMode groupMode;//

  public enum GroupMode {
    GROUP_MODE_LOADER_OPEN,
    GROUP_MODE_LOADER_INVITED,
    GROUP_MODE_LOADER_LEFT
  }

  public ConversationListLoader(Context context, String filter, boolean archived, GroupMode groupMode) {
    super(context);
    this.filter   = filter;
    this.archived = archived;
    this.groupMode  = groupMode;//
  }

  //
  @Override
  public Cursor getCursor() {

    switch (groupMode)
    {
      case GROUP_MODE_LOADER_OPEN:
        if      (filter != null && filter.trim().length() != 0) return getFilteredConversationList(filter);
        else if (!archived)                                     return DatabaseFactory.getThreadDatabase(context).getUnarchivedOpenConversationList();//return getUnarchivedConversationList();
        else                                                    return getArchivedConversationList();

      case GROUP_MODE_LOADER_INVITED:
        if      (filter != null && filter.trim().length() != 0) return getFilteredConversationList(filter);
        else if (!archived)                                     return  DatabaseFactory.getThreadDatabase(context).getInvitedConversationList();
        else                                                    return getArchivedConversationList();

      case GROUP_MODE_LOADER_LEFT:
        if      (filter != null && filter.trim().length() != 0) return getFilteredConversationList(filter);
        else if (!archived)                                     return DatabaseFactory.getThreadDatabase(context).getLeftConversationList();//return getUnarchivedConversationList();
        else                                                    return getArchivedConversationList();
    }

    return null;
  }


  // orig
  private Cursor getUnarchivedConversationList() {
    List<Cursor> cursorList = new LinkedList<>();
    cursorList.add(DatabaseFactory.getThreadDatabase(context).getConversationList());

    int archivedCount = DatabaseFactory.getThreadDatabase(context)
            .getArchivedConversationListCount();

    if (archivedCount > 0) {
      MatrixCursor switchToArchiveCursor = new MatrixCursor(new String[] {
              ThreadDatabase.ID, ThreadDatabase.DATE, ThreadDatabase.MESSAGE_COUNT,
              ThreadDatabase.ADDRESS, ThreadDatabase.SNIPPET, ThreadDatabase.READ, ThreadDatabase.UNREAD_COUNT,
              ThreadDatabase.TYPE, ThreadDatabase.SNIPPET_TYPE, ThreadDatabase.SNIPPET_URI,
              ThreadDatabase.SNIPPET_CONTENT_TYPE, ThreadDatabase.SNIPPET_EXTRAS,
              ThreadDatabase.ARCHIVED, ThreadDatabase.STATUS, ThreadDatabase.DELIVERY_RECEIPT_COUNT,
              ThreadDatabase.EXPIRES_IN, ThreadDatabase.LAST_SEEN, ThreadDatabase.READ_RECEIPT_COUNT, ThreadDatabase.UFSRV_COMMAND, ThreadDatabase.UFSRV_FID, ThreadDatabase.UFSRV_EID}, 1); // UFSRV

      //https://github.com/signalapp/Signal-Android/commit/9c50057909c7fbb40842dc8e283194c821336ad2
      if (cursorList.get(0).getCount() <= 0) {
          switchToArchiveCursor.addRow(new Object[] {-1L, System.currentTimeMillis(), archivedCount,
            "-1", null, 1, 0, ThreadDatabase.DistributionTypes.INBOX_ZERO,
                  0, null, null, null, 0, -1, 0, 0, 0, -1});
        }

      // additional row to tell how many archived rows there are
      switchToArchiveCursor.addRow(new Object[] {-1L, System.currentTimeMillis(), archivedCount,
              "-1", null, 1, 0, ThreadDatabase.DistributionTypes.ARCHIVE,
              0, null, 0, -1, 0, 0, 0, -1,
              null, 0, 0, 0});//

      cursorList.add(switchToArchiveCursor);
    }

    return new MergeCursor(cursorList.toArray(new Cursor[0]));
  }

  private Cursor getArchivedConversationList() {
    return DatabaseFactory.getThreadDatabase(context).getArchivedConversationList();
  }

  private Cursor getFilteredConversationList(String filter) {
    List<String> numbers = ContactAccessor.getInstance().getNumbersForThreadSearchFilter(context, filter);
    List<Address> addresses = new LinkedList<>();

     for (String number : numbers) {
      addresses.add(Address.fromExternal(context, number));
    }

    return DatabaseFactory.getThreadDatabase(context).getFilteredConversationList(addresses);
  }
}
