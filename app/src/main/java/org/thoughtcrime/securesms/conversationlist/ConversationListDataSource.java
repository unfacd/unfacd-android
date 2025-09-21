package org.thoughtcrime.securesms.conversationlist;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;

import org.signal.core.util.logging.Log;
import org.signal.paging.PagedDataSource;
import org.thoughtcrime.securesms.conversationlist.model.Conversation;
import org.thoughtcrime.securesms.conversationlist.model.ConversationReader;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Stopwatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

abstract class ConversationListDataSource implements PagedDataSource<Long, Conversation> {

  private static final String TAG = Log.tag(ConversationListDataSource.class);

  protected final ThreadDatabase threadDatabase;


  protected ConversationListDataSource(@NonNull Context context) {
    this.threadDatabase = SignalDatabase.threads();
  }

  public static ConversationListDataSource create(@NonNull Context context, boolean isArchived, GroupMode groupMode) {//AA+ groupMode
//    if (!isArchived) return new UnarchivedConversationListDataSource(context, invalidator);
//    else             return new ArchivedConversationListDataSource(context, invalidator);

    //AA+
    switch (groupMode) {
      case GROUP_MODE_LOADER_OPEN:
      default:
        return new UnarchivedConversationListDataSource(context);

      case GROUP_MODE_LOADER_INVITED:
        return new InvitedConversationListDataSource(context);

      case GROUP_MODE_LOADER_LEFT:
        return new LeftConversationListDataSource(context);

      case GROUP_MODE_LOADER_GUARDIAN:
        return new GuardianConversationListDataSource(context);

      case GROUP_MODE_LOADER_ARCHIVED:
        return new ArchivedConversationListDataSource(context);
    }
  }

  @Override
  public int size() {
    long startTime = System.currentTimeMillis();
    int  count     = getTotalCount();

    Log.d(TAG, "[size(), " + getClass().getSimpleName() + "] " + (System.currentTimeMillis() - startTime) + " ms");
    return count;
  }

  @Override
  public @NonNull List<Conversation> load(int start, int length, @NonNull CancellationSignal cancellationSignal) {
    Stopwatch stopwatch = new Stopwatch("load(" + start + ", " + length + "), " + getClass().getSimpleName());

    List<Conversation> conversations  = new ArrayList<>(length);
    List<Recipient>  recipients       = new LinkedList<>();
    Set<RecipientId> needsResolve     = new HashSet<>();

    try (ConversationReader reader = new ConversationReader(getCursor(start, length))) {
      ThreadRecord record;
      while ((record = reader.getNext()) != null && !cancellationSignal.isCanceled()) {
        conversations.add(new Conversation(record));
        recipients.add(record.getRecipient());

        if (!record.getRecipient().isPushV2Group()) {
          needsResolve.add(record.getRecipient().getId());
        } else if (SmsDatabase.Types.isGroupUpdate(record.getType())) {
          List<RecipientId> recipientIds = MessageRecord.getMentionsRecipients(ApplicationDependencies.getApplication(), record.getUfsrvCommand());//AA+
          needsResolve.addAll(recipientIds);
        }
      }
    }

    stopwatch.split("cursor");

    ApplicationDependencies.getRecipientCache().addToCache(recipients);
    stopwatch.split("cache-recipients");

    Recipient.resolvedList(needsResolve);
    stopwatch.split("recipient-resolve");

    stopwatch.stop(TAG);

    return conversations;
  }

  @Override
  public @Nullable Conversation load(Long threadId) {
    throw new UnsupportedOperationException("Not implemented!");
  }

  @Override
  public @NonNull Long getKey(@NonNull Conversation conversation) {
    return conversation.getThreadRecord().getThreadId();
  }

  protected abstract int getTotalCount();
  protected abstract Cursor getCursor(long offset, long limit);

  private static class ArchivedConversationListDataSource extends ConversationListDataSource {

    ArchivedConversationListDataSource(@NonNull Context context) {
      super(context);
    }

    @Override
    protected int getTotalCount() {
      return threadDatabase.getArchivedConversationListCount();
    }

    @Override
    protected Cursor getCursor(long offset, long limit) {
      return threadDatabase.getArchivedConversationList(offset, limit);
    }
  }

  @VisibleForTesting
  static class UnarchivedConversationListDataSource extends ConversationListDataSource {

    private int totalCount;
    private int pinnedCount;
    private int archivedCount;
    private int unpinnedCount;

    UnarchivedConversationListDataSource(@NonNull Context context) {
      super(context);
    }

    @Override
    protected int getTotalCount() {
      int unarchivedCount = threadDatabase.getAllUnarchivedOpenConversationListCount();

      pinnedCount   = threadDatabase.getPinnedUnarchivedOpenConversationListCount();
      archivedCount = 0;//threadDatabase.getArchivedConversationListCount(); //AA-
      unpinnedCount = unarchivedCount - pinnedCount;
      totalCount    = unarchivedCount;

      if (archivedCount != 0) {
        totalCount++;
      }

      if (pinnedCount != 0) {
        if (unpinnedCount != 0) {
          totalCount += 2;
        } else {
          totalCount += 1;
        }
      }

      return totalCount;
    }

    @Override
    protected Cursor getCursor(long offset, long limit) {
      List<Cursor> cursors       = new ArrayList<>(5);
      long         originalLimit = limit;

      if (offset == 0 && hasPinnedHeader()) {
        MatrixCursor pinnedHeaderCursor = new MatrixCursor(ConversationReader.HEADER_COLUMN);
        pinnedHeaderCursor.addRow(ConversationReader.PINNED_HEADER);
        cursors.add(pinnedHeaderCursor);
        limit--;
      }

      Cursor pinnedCursor = threadDatabase.getUnarchivedOpenConversationList(true, offset, limit);
      cursors.add(pinnedCursor);
      limit -= pinnedCursor.getCount();

      if (offset == 0 && hasUnpinnedHeader()) {
        MatrixCursor unpinnedHeaderCursor = new MatrixCursor(ConversationReader.HEADER_COLUMN);
        unpinnedHeaderCursor.addRow(ConversationReader.UNPINNED_HEADER);
        cursors.add(unpinnedHeaderCursor);
        limit--;
      }

      long   unpinnedOffset = Math.max(0, offset - pinnedCount - getHeaderOffset());
      Cursor unpinnedCursor = threadDatabase.getUnarchivedOpenConversationList(false, unpinnedOffset, limit);
      cursors.add(unpinnedCursor);

      if (offset + originalLimit >= totalCount && hasArchivedFooter()) {
        MatrixCursor archivedFooterCursor = new MatrixCursor(ConversationReader.ARCHIVED_COLUMNS);
        archivedFooterCursor.addRow(ConversationReader.createArchivedFooterRow(archivedCount));
        cursors.add(archivedFooterCursor);
      }

      return new MergeCursor(cursors.toArray(new Cursor[]{}));
    }

    @VisibleForTesting
    int getHeaderOffset() {
      return (hasPinnedHeader() ? 1 : 0) + (hasUnpinnedHeader() ? 1 : 0);
    }

    @VisibleForTesting
    boolean hasPinnedHeader() {
      return pinnedCount != 0;
    }

    @VisibleForTesting
    boolean hasUnpinnedHeader() {
      return hasPinnedHeader() && unpinnedCount != 0;
    }

    @VisibleForTesting
    boolean hasArchivedFooter() {
      return archivedCount != 0;
    }
  }

  private static class LeftConversationListDataSource extends ConversationListDataSource {

    LeftConversationListDataSource(@NonNull Context context) {
      super(context);
    }

    @Override
    protected int getTotalCount() {
      return threadDatabase.getLeftConversationListCount();
    }

    @Override
    protected Cursor getCursor(long offset, long limit) {
      return threadDatabase.getLeftConversationList(offset, limit);
    }
  }

  private static class GuardianConversationListDataSource extends ConversationListDataSource {

    GuardianConversationListDataSource(@NonNull Context context) {
      super(context);
    }

    @Override
    protected int getTotalCount() {
      return threadDatabase.getGuardianConversationListCount();
    }

    @Override
    protected Cursor getCursor(long offset, long limit) {
      return threadDatabase.getGuardianConversationList(offset, limit);
    }
  }

  @VisibleForTesting
  static class InvitedConversationListDataSource extends ConversationListDataSource {

    private int totalCount;
    private int pinnedCount;
    private int archivedCount;
    private int unpinnedCount;

    InvitedConversationListDataSource(@NonNull Context context) {
      super(context);
    }

    @Override
    protected int getTotalCount() {
      int unarchivedCount = threadDatabase.getAllInvitedConversationListCount();

      pinnedCount   = threadDatabase.getInvitedPinnedConversationListCount();
      archivedCount = 0;
      unpinnedCount = unarchivedCount - pinnedCount;
      totalCount    = unarchivedCount;

      if (archivedCount != 0) {
        totalCount++;
      }

      if (pinnedCount != 0) {
        if (unpinnedCount != 0) {
          totalCount += 2;
        } else {
          totalCount += 1;
        }
      }

      return totalCount;
    }

    @Override
    protected Cursor getCursor(long offset, long limit) {
      List<Cursor> cursors       = new ArrayList<>(5);
      long         originalLimit = limit;

      if (offset == 0 && hasPinnedHeader()) {
        MatrixCursor pinnedHeaderCursor = new MatrixCursor(ConversationReader.HEADER_COLUMN);
        pinnedHeaderCursor.addRow(ConversationReader.PINNED_HEADER);
        cursors.add(pinnedHeaderCursor);
        limit--;
      }

      Cursor pinnedCursor = threadDatabase.getInvitedPinnedConversationList(offset, limit);
      cursors.add(pinnedCursor);
      limit -= pinnedCursor.getCount();

      if (offset == 0 && hasUnpinnedHeader()) {
        MatrixCursor unpinnedHeaderCursor = new MatrixCursor(ConversationReader.HEADER_COLUMN);
        unpinnedHeaderCursor.addRow(ConversationReader.UNPINNED_HEADER);
        cursors.add(unpinnedHeaderCursor);
        limit--;
      }

      long   unpinnedOffset = Math.max(0, offset - pinnedCount - getHeaderOffset());
      Cursor unpinnedCursor = threadDatabase.getInvitedUnpinnedConversationList(unpinnedOffset, limit);
      cursors.add(unpinnedCursor);

      if (offset + originalLimit >= totalCount && hasArchivedFooter()) {
        MatrixCursor archivedFooterCursor = new MatrixCursor(ConversationReader.ARCHIVED_COLUMNS);
        archivedFooterCursor.addRow(ConversationReader.createArchivedFooterRow(archivedCount));
        cursors.add(archivedFooterCursor);
      }

      return new MergeCursor(cursors.toArray(new Cursor[]{}));
    }

    @VisibleForTesting
    int getHeaderOffset() {
      return (hasPinnedHeader() ? 1 : 0) + (hasUnpinnedHeader() ? 1 : 0);
    }

    @VisibleForTesting
    boolean hasPinnedHeader() {
      return pinnedCount != 0;
    }

    @VisibleForTesting
    boolean hasUnpinnedHeader() {
      return hasPinnedHeader() && unpinnedCount != 0;
    }

    @VisibleForTesting
    boolean hasArchivedFooter() {
      return archivedCount != 0;
    }
  }

}