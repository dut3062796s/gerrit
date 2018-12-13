// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.notedb;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.server.notedb.NoteDbTable.CHANGES;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.git.RefUpdateUtil;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.InMemoryInserter;
import com.google.gerrit.server.git.InsertedObject;
import com.google.gerrit.server.notedb.NoteDbChangeState.PrimaryStorage;
import com.google.gerrit.server.update.ChainedReceiveCommands;
import com.google.gerrit.server.update.RetryingRestModifyView;
import com.google.gwtorm.server.OrmConcurrencyException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushCertificate;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Object to manage a single sequence of updates to NoteDb.
 *
 * <p>Instances are one-time-use. Handles updating both the change repo and the All-Users repo for
 * any affected changes, with proper ordering.
 *
 * <p>To see the state that would be applied prior to executing the full sequence of updates, use
 * {@link #stage()}.
 */
public class NoteDbUpdateManager implements AutoCloseable {
  private static final ImmutableList<String> PACKAGE_PREFIXES =
      ImmutableList.of("com.google.gerrit.server.", "com.google.gerrit.");
  private static final ImmutableSet<String> SERVLET_NAMES =
      ImmutableSet.of(
          "com.google.gerrit.httpd.restapi.RestApiServlet", RetryingRestModifyView.class.getName());

  public interface Factory {
    NoteDbUpdateManager create(Project.NameKey projectName);
  }

  public static class OpenRepo implements AutoCloseable {
    public final Repository repo;
    public final RevWalk rw;
    public final ChainedReceiveCommands cmds;

    private final InMemoryInserter inMemIns;
    private final ObjectInserter tempIns;
    @Nullable private final ObjectInserter finalIns;

    private final boolean close;

    private OpenRepo(
        Repository repo,
        RevWalk rw,
        @Nullable ObjectInserter ins,
        ChainedReceiveCommands cmds,
        boolean close) {
      ObjectReader reader = rw.getObjectReader();
      checkArgument(
          ins == null || reader.getCreatedFromInserter() == ins,
          "expected reader to be created from %s, but was %s",
          ins,
          reader.getCreatedFromInserter());
      this.repo = requireNonNull(repo);

      this.inMemIns = new InMemoryInserter(rw.getObjectReader());
      this.tempIns = inMemIns;

      this.rw = new RevWalk(tempIns.newReader());
      this.finalIns = ins;
      this.cmds = requireNonNull(cmds);
      this.close = close;
    }

    public Optional<ObjectId> getObjectId(String refName) throws IOException {
      return cmds.get(refName);
    }

    void flush() throws IOException {
      flushToFinalInserter();
      finalIns.flush();
    }

    void flushToFinalInserter() throws IOException {
      checkState(finalIns != null);
      for (InsertedObject obj : inMemIns.getInsertedObjects()) {
        finalIns.insert(obj.type(), obj.data().toByteArray());
      }
      inMemIns.clear();
    }

    @Override
    public void close() {
      rw.getObjectReader().close();
      rw.close();
      if (close) {
        if (finalIns != null) {
          finalIns.close();
        }
        repo.close();
      }
    }
  }

  private final Provider<PersonIdent> serverIdent;
  private final GitRepositoryManager repoManager;
  private final AllUsersName allUsersName;
  private final NoteDbMetrics metrics;
  private final Project.NameKey projectName;
  private final ListMultimap<String, ChangeUpdate> changeUpdates;
  private final ListMultimap<String, ChangeDraftUpdate> draftUpdates;
  private final ListMultimap<String, RobotCommentUpdate> robotCommentUpdates;
  private final ListMultimap<String, NoteDbRewriter> rewriters;
  private final Set<Change.Id> toDelete;

  private OpenRepo changeRepo;
  private OpenRepo allUsersRepo;
  private boolean executed;
  private String refLogMessage;
  private PersonIdent refLogIdent;
  private PushCertificate pushCert;

  @Inject
  NoteDbUpdateManager(
      @GerritPersonIdent Provider<PersonIdent> serverIdent,
      GitRepositoryManager repoManager,
      AllUsersName allUsersName,
      NoteDbMetrics metrics,
      @Assisted Project.NameKey projectName) {
    this.serverIdent = serverIdent;
    this.repoManager = repoManager;
    this.allUsersName = allUsersName;
    this.metrics = metrics;
    this.projectName = projectName;
    changeUpdates = MultimapBuilder.hashKeys().arrayListValues().build();
    draftUpdates = MultimapBuilder.hashKeys().arrayListValues().build();
    robotCommentUpdates = MultimapBuilder.hashKeys().arrayListValues().build();
    rewriters = MultimapBuilder.hashKeys().arrayListValues().build();
    toDelete = new HashSet<>();
  }

  @Override
  public void close() {
    try {
      if (allUsersRepo != null) {
        OpenRepo r = allUsersRepo;
        allUsersRepo = null;
        r.close();
      }
    } finally {
      if (changeRepo != null) {
        OpenRepo r = changeRepo;
        changeRepo = null;
        r.close();
      }
    }
  }

  public NoteDbUpdateManager setChangeRepo(
      Repository repo, RevWalk rw, @Nullable ObjectInserter ins, ChainedReceiveCommands cmds) {
    checkState(changeRepo == null, "change repo already initialized");
    changeRepo = new OpenRepo(repo, rw, ins, cmds, false);
    return this;
  }

  public NoteDbUpdateManager setRefLogMessage(String message) {
    this.refLogMessage = message;
    return this;
  }

  public NoteDbUpdateManager setRefLogIdent(PersonIdent ident) {
    this.refLogIdent = ident;
    return this;
  }

  /**
   * Set a push certificate for the push that originally triggered this NoteDb update.
   *
   * <p>The pusher will not necessarily have specified any of the NoteDb refs explicitly, such as
   * when processing a push to {@code refs/for/master}. That's fine; this is just passed to the
   * underlying {@link BatchRefUpdate}, and the implementation decides what to do with it.
   *
   * <p>The cert should be associated with the main repo. There is currently no way of associating a
   * push cert with the {@code All-Users} repo, since it is not currently possible to update draft
   * changes via push.
   *
   * @param pushCert push certificate; may be null.
   * @return this
   */
  public NoteDbUpdateManager setPushCertificate(PushCertificate pushCert) {
    this.pushCert = pushCert;
    return this;
  }

  private void initChangeRepo() throws IOException {
    if (changeRepo == null) {
      changeRepo = openRepo(projectName);
    }
  }

  private void initAllUsersRepo() throws IOException {
    if (allUsersRepo == null) {
      allUsersRepo = openRepo(allUsersName);
    }
  }

  private OpenRepo openRepo(Project.NameKey p) throws IOException {
    Repository repo = repoManager.openRepository(p); // Closed by OpenRepo#close.
    ObjectInserter ins = repo.newObjectInserter(); // Closed by OpenRepo#close.
    ObjectReader reader = ins.newReader(); // Not closed by OpenRepo#close.
    try (RevWalk rw = new RevWalk(reader)) { // Doesn't escape OpenRepo constructor.
      return new OpenRepo(repo, rw, ins, new ChainedReceiveCommands(repo), true) {
        @Override
        public void close() {
          reader.close();
          super.close();
        }
      };
    }
  }

  private boolean isEmpty() {
    return changeUpdates.isEmpty()
        && draftUpdates.isEmpty()
        && robotCommentUpdates.isEmpty()
        && rewriters.isEmpty()
        && toDelete.isEmpty()
        && !hasCommands(changeRepo)
        && !hasCommands(allUsersRepo);
  }

  private static boolean hasCommands(@Nullable OpenRepo or) {
    return or != null && !or.cmds.isEmpty();
  }

  /**
   * Add an update to the list of updates to execute.
   *
   * <p>Updates should only be added to the manager after all mutations have been made, as this
   * method may eagerly access the update.
   *
   * @param update the update to add.
   */
  public void add(ChangeUpdate update) {
    checkNotExecuted();
    checkArgument(
        update.getProjectName().equals(projectName),
        "update for project %s cannot be added to manager for project %s",
        update.getProjectName(),
        projectName);
    checkArgument(
        !rewriters.containsKey(update.getRefName()),
        "cannot update & rewrite ref %s in one BatchUpdate",
        update.getRefName());

    ChangeDraftUpdate du = update.getDraftUpdate();
    if (du != null) {
      draftUpdates.put(du.getRefName(), du);
    }
    RobotCommentUpdate rcu = update.getRobotCommentUpdate();
    if (rcu != null) {
      robotCommentUpdates.put(rcu.getRefName(), rcu);
    }
    DeleteCommentRewriter deleteCommentRewriter = update.getDeleteCommentRewriter();
    if (deleteCommentRewriter != null) {
      // Checks whether there is any ChangeUpdate or rewriter added earlier for the same ref.
      checkArgument(
          !changeUpdates.containsKey(deleteCommentRewriter.getRefName()),
          "cannot update & rewrite ref %s in one BatchUpdate",
          deleteCommentRewriter.getRefName());
      checkArgument(
          !rewriters.containsKey(deleteCommentRewriter.getRefName()),
          "cannot rewrite the same ref %s in one BatchUpdate",
          deleteCommentRewriter.getRefName());
      rewriters.put(deleteCommentRewriter.getRefName(), deleteCommentRewriter);
    }

    DeleteChangeMessageRewriter deleteChangeMessageRewriter =
        update.getDeleteChangeMessageRewriter();
    if (deleteChangeMessageRewriter != null) {
      // Checks whether there is any ChangeUpdate or rewriter added earlier for the same ref.
      checkArgument(
          !changeUpdates.containsKey(deleteChangeMessageRewriter.getRefName()),
          "cannot update & rewrite ref %s in one BatchUpdate",
          deleteChangeMessageRewriter.getRefName());
      checkArgument(
          !rewriters.containsKey(deleteChangeMessageRewriter.getRefName()),
          "cannot rewrite the same ref %s in one BatchUpdate",
          deleteChangeMessageRewriter.getRefName());
      rewriters.put(deleteChangeMessageRewriter.getRefName(), deleteChangeMessageRewriter);
    }

    changeUpdates.put(update.getRefName(), update);
  }

  public void add(ChangeDraftUpdate draftUpdate) {
    checkNotExecuted();
    draftUpdates.put(draftUpdate.getRefName(), draftUpdate);
  }

  public void deleteChange(Change.Id id) {
    checkNotExecuted();
    toDelete.add(id);
  }

  /**
   * Stage updates in the manager's internal list of commands.
   *
   * @throws OrmException if a database layer error occurs.
   * @throws IOException if a storage layer error occurs.
   */
  private void stage() throws OrmException, IOException {
    try (Timer1.Context timer = metrics.stageUpdateLatency.start(CHANGES)) {
      if (isEmpty()) {
        return;
      }

      initChangeRepo();
      if (!draftUpdates.isEmpty() || !toDelete.isEmpty()) {
        initAllUsersRepo();
      }
      checkExpectedState();
      addCommands();
    }
  }

  public void flush() throws IOException {
    checkNotExecuted();
    if (changeRepo != null) {
      changeRepo.flush();
    }
    if (allUsersRepo != null) {
      allUsersRepo.flush();
    }
  }

  @Nullable
  public BatchRefUpdate execute() throws OrmException, IOException {
    return execute(false);
  }

  @Nullable
  public BatchRefUpdate execute(boolean dryrun) throws OrmException, IOException {
    checkNotExecuted();
    if (isEmpty()) {
      executed = true;
      return null;
    }
    try (Timer1.Context timer = metrics.updateLatency.start(CHANGES)) {
      stage();
      // ChangeUpdates must execute before ChangeDraftUpdates.
      //
      // ChangeUpdate will automatically delete draft comments for any published
      // comments, but the updates to the two repos don't happen atomically.
      // Thus if the change meta update succeeds and the All-Users update fails,
      // we may have stale draft comments. Doing it in this order allows stale
      // comments to be filtered out by ChangeNotes, reflecting the fact that
      // comments can only go from DRAFT to PUBLISHED, not vice versa.
      BatchRefUpdate result = execute(changeRepo, dryrun, pushCert);
      execute(allUsersRepo, dryrun, null);
      executed = true;
      return result;
    } finally {
      close();
    }
  }

  private BatchRefUpdate execute(OpenRepo or, boolean dryrun, @Nullable PushCertificate pushCert)
      throws IOException {
    if (or == null || or.cmds.isEmpty()) {
      return null;
    }
    if (!dryrun) {
      or.flush();
    } else {
      // OpenRepo buffers objects separately; caller may assume that objects are available in the
      // inserter it previously passed via setChangeRepo.
      or.flushToFinalInserter();
    }

    BatchRefUpdate bru = or.repo.getRefDatabase().newBatchUpdate();
    bru.setPushCertificate(pushCert);
    if (refLogMessage != null) {
      bru.setRefLogMessage(refLogMessage, false);
    } else {
      bru.setRefLogMessage(firstNonNull(guessRestApiHandler(), "Update NoteDb refs"), false);
    }
    bru.setRefLogIdent(refLogIdent != null ? refLogIdent : serverIdent.get());
    bru.setAtomic(true);
    or.cmds.addTo(bru);
    bru.setAllowNonFastForwards(true);

    if (!dryrun) {
      RefUpdateUtil.executeChecked(bru, or.rw);
    }
    return bru;
  }

  private static String guessRestApiHandler() {
    StackTraceElement[] trace = Thread.currentThread().getStackTrace();
    int i = findRestApiServlet(trace);
    if (i < 0) {
      return null;
    }
    try {
      for (i--; i >= 0; i--) {
        String cn = trace[i].getClassName();
        Class<?> cls = Class.forName(cn);
        if (RestModifyView.class.isAssignableFrom(cls) && cls != RetryingRestModifyView.class) {
          return viewName(cn);
        }
      }
      return null;
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private static String viewName(String cn) {
    String impl = cn.replace('$', '.');
    for (String p : PACKAGE_PREFIXES) {
      if (impl.startsWith(p)) {
        return impl.substring(p.length());
      }
    }
    return impl;
  }

  private static int findRestApiServlet(StackTraceElement[] trace) {
    for (int i = 0; i < trace.length; i++) {
      if (SERVLET_NAMES.contains(trace[i].getClassName())) {
        return i;
      }
    }
    return -1;
  }

  private void addCommands() throws OrmException, IOException {
    if (isEmpty()) {
      return;
    }
    checkState(changeRepo != null, "must set change repo");
    if (!draftUpdates.isEmpty()) {
      checkState(allUsersRepo != null, "must set all users repo");
    }
    addUpdates(changeUpdates, changeRepo);
    if (!draftUpdates.isEmpty()) {
      addUpdates(draftUpdates, allUsersRepo);
    }
    if (!robotCommentUpdates.isEmpty()) {
      addUpdates(robotCommentUpdates, changeRepo);
    }
    if (!rewriters.isEmpty()) {
      addRewrites(rewriters, changeRepo);
    }

    for (Change.Id id : toDelete) {
      doDelete(id);
    }
    checkExpectedState();
  }

  private void doDelete(Change.Id id) throws IOException {
    String metaRef = RefNames.changeMetaRef(id);
    Optional<ObjectId> old = changeRepo.cmds.get(metaRef);
    if (old.isPresent()) {
      changeRepo.cmds.add(new ReceiveCommand(old.get(), ObjectId.zeroId(), metaRef));
    }

    // Just scan repo for ref names, but get "old" values from cmds.
    for (Ref r :
        allUsersRepo.repo.getRefDatabase().getRefsByPrefix(RefNames.refsDraftCommentsPrefix(id))) {
      old = allUsersRepo.cmds.get(r.getName());
      if (old.isPresent()) {
        allUsersRepo.cmds.add(new ReceiveCommand(old.get(), ObjectId.zeroId(), r.getName()));
      }
    }
  }

  public static class MismatchedStateException extends OrmException {
    private static final long serialVersionUID = 1L;

    private MismatchedStateException(Change.Id id, NoteDbChangeState expectedState) {
      super(
          String.format(
              "cannot apply NoteDb updates for change %s; change meta ref does not match %s",
              id, expectedState.getChangeMetaId().name()));
    }
  }

  private void checkNotExecuted() {
    checkState(!executed, "update has already been executed");
  }

  private void checkExpectedState() throws OrmException, IOException {
    // Refuse to apply an update unless the state in NoteDb matches the state
    // claimed in the ref. This means we may have failed a NoteDb ref update,
    // and it would be incorrect to claim that the ref is up to date after this
    // pipeline.
    //
    // Generally speaking, this case should be rare; in most cases, we should
    // have detected and auto-fixed the stale state when creating ChangeNotes
    // that got passed into the ChangeUpdate.
    for (Collection<ChangeUpdate> us : changeUpdates.asMap().values()) {
      ChangeUpdate u = us.iterator().next();
      NoteDbChangeState expectedState = NoteDbChangeState.parse(u.getChange());

      if (expectedState == null) {
        // No previous state means we haven't previously written NoteDb graphs
        // for this change yet. This means either:
        //  - The change is new, and we'll be creating its ref.
        //  - We short-circuited before adding any commands that update this
        //    ref, and we won't stage a delta for this change either.
        // Either way, it is safe to proceed here rather than throwing
        // MismatchedStateException.
        continue;
      }

      if (expectedState.getPrimaryStorage() == PrimaryStorage.NOTE_DB) {
        // NoteDb is primary, no need to compare state to ReviewDb.
        continue;
      }

      if (!expectedState.isChangeUpToDate(changeRepo.cmds.getRepoRefCache())) {
        throw new MismatchedStateException(u.getId(), expectedState);
      }
    }

    for (Collection<ChangeDraftUpdate> us : draftUpdates.asMap().values()) {
      ChangeDraftUpdate u = us.iterator().next();
      NoteDbChangeState expectedState = NoteDbChangeState.parse(u.getChange());

      if (expectedState == null || expectedState.getPrimaryStorage() == PrimaryStorage.NOTE_DB) {
        continue; // See above.
      }

      Account.Id accountId = u.getAccountId();
      if (!expectedState.areDraftsUpToDate(allUsersRepo.cmds.getRepoRefCache(), accountId)) {
        ObjectId expectedDraftId =
            firstNonNull(expectedState.getDraftIds().get(accountId), ObjectId.zeroId());
        throw new OrmConcurrencyException(
            String.format(
                "cannot apply NoteDb updates for change %s;"
                    + " draft ref for account %s does not match %s",
                u.getId(), accountId, expectedDraftId.name()));
      }
    }
  }

  private static <U extends AbstractChangeUpdate> void addUpdates(
      ListMultimap<String, U> all, OpenRepo or) throws OrmException, IOException {
    for (Map.Entry<String, Collection<U>> e : all.asMap().entrySet()) {
      String refName = e.getKey();
      Collection<U> updates = e.getValue();
      ObjectId old = or.cmds.get(refName).orElse(ObjectId.zeroId());
      // Only actually write to the ref if one of the updates explicitly allows
      // us to do so, i.e. it is known to represent a new change. This avoids
      // writing partial change meta if the change hasn't been backfilled yet.
      if (!allowWrite(updates, old)) {
        continue;
      }

      ObjectId curr = old;
      for (U u : updates) {
        if (u.isRootOnly() && !old.equals(ObjectId.zeroId())) {
          throw new OrmException("Given ChangeUpdate is only allowed on initial commit");
        }
        ObjectId next = u.apply(or.rw, or.tempIns, curr);
        if (next == null) {
          continue;
        }
        curr = next;
      }
      if (!old.equals(curr)) {
        or.cmds.add(new ReceiveCommand(old, curr, refName));
      }
    }
  }

  private static void addRewrites(ListMultimap<String, NoteDbRewriter> rewriters, OpenRepo openRepo)
      throws OrmException, IOException {
    for (Map.Entry<String, Collection<NoteDbRewriter>> entry : rewriters.asMap().entrySet()) {
      String refName = entry.getKey();
      ObjectId oldTip = openRepo.cmds.get(refName).orElse(ObjectId.zeroId());

      if (oldTip.equals(ObjectId.zeroId())) {
        throw new OrmException(String.format("Ref %s is empty", refName));
      }

      ObjectId currTip = oldTip;
      try {
        for (NoteDbRewriter noteDbRewriter : entry.getValue()) {
          ObjectId nextTip =
              noteDbRewriter.rewriteCommitHistory(openRepo.rw, openRepo.tempIns, currTip);
          if (nextTip != null) {
            currTip = nextTip;
          }
        }
      } catch (ConfigInvalidException e) {
        throw new OrmException("Cannot rewrite commit history", e);
      }

      if (!oldTip.equals(currTip)) {
        openRepo.cmds.add(new ReceiveCommand(oldTip, currTip, refName));
      }
    }
  }

  private static <U extends AbstractChangeUpdate> boolean allowWrite(
      Collection<U> updates, ObjectId old) {
    if (!old.equals(ObjectId.zeroId())) {
      return true;
    }
    return updates.iterator().next().allowWriteToNewRef();
  }
}
