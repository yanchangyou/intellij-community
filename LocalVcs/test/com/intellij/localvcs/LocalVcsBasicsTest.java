package com.intellij.localvcs;

import java.util.List;

import org.junit.Test;

public class LocalVcsBasicsTest extends TestCase {
  // todo clean up LocalVcs tests
  private LocalVcs vcs = new LocalVcs(new TestStorage());

  @Test
  public void testOnlyCommitThrowsException() {
    vcs.createFile(p("file"), "");
    vcs.createFile(p("file"), "");

    try {
      vcs.apply();
      fail();
    } catch (LocalVcsException e) { }
  }

  @Test
  public void testDoesNotApplyAnyChangeIfCommitFail() {
    vcs.createFile(p("file1"), "");
    vcs.createFile(p("file2"), "");
    vcs.createFile(p("file2"), "");

    try { vcs.apply(); } catch (LocalVcsException e) { }

    assertFalse(vcs.hasEntry(p("file1")));
    assertFalse(vcs.hasEntry(p("file2")));
  }

  @Test
  public void testClearingChangesOnCommit() {
    vcs.createFile(p("file"), "content");
    vcs.changeFileContent(p("file"), "new content");
    vcs.rename(p("file"), "new file");
    vcs.delete(p("new file"));

    assertFalse(vcs.isClean());

    vcs.apply();
    assertTrue(vcs.isClean());
  }

  @Test
  public void testDoesNotChangeContentBeforeCommit() {
    // todo rename to doesNotMakeAnyChangesBeforeCommit
    vcs.createFile(p("file"), "content");
    vcs.apply();

    vcs.changeFileContent(p("file"), "new content");

    assertEquals("content", vcs.getEntry(p("file")).getContent());
  }

  @Test
  public void testIncrementingIdOnEntryCreation() {
    vcs.createDirectory(p("dir"));
    vcs.createFile(p("file"), null);
    vcs.apply();

    Integer id1 = vcs.getEntry(p("dir")).getObjectId();
    Integer id2 = vcs.getEntry(p("file")).getObjectId();

    assertFalse(id1.equals(id2));
  }

  @Test
  public void testKeepingIdOnChangingFileContent() {
    vcs.createFile(p("file"), "content");
    vcs.apply();
    Integer id1 = vcs.getEntry(p("file")).getObjectId();

    vcs.changeFileContent(p("file"), "new content");
    vcs.apply();
    Integer id2 = vcs.getEntry(p("file")).getObjectId();

    assertEquals(id1, id2);
  }

  @Test
  public void testKeepingIdOnRenamingFile() {
    vcs.createFile(p("file"), null);
    vcs.apply();
    Integer id1 = vcs.getEntry(p("file")).getObjectId();

    vcs.rename(p("file"), "new file");
    vcs.apply();
    Integer id2 = vcs.getEntry(p("new file")).getObjectId();

    assertEquals(id1, id2);
  }

  @Test
  public void testKeepingIdOnMovingFile() {
    vcs.createDirectory(p("dir1"));
    vcs.createDirectory(p("dir2"));
    vcs.createFile(p("dir1/file"), null);
    vcs.apply();
    Integer id1 = vcs.getEntry(p("dir1/file")).getObjectId();

    vcs.move(p("dir1/file"), p("dir2"));
    vcs.apply();
    Integer id2 = vcs.getEntry(p("dir2/file")).getObjectId();

    assertEquals(id1, id2);
  }

  @Test
  public void testKeepingIdOnRestoringDeletedFile() {
    vcs.createFile(p("file"), null);
    vcs.apply();
    Integer id1 = vcs.getEntry(p("file")).getObjectId();

    vcs.delete(p("file"));
    vcs.apply();

    vcs.revert();
    Integer id2 = vcs.getEntry(p("file")).getObjectId();

    assertEquals(id1, id2);
  }

  @Test
  public void testHistory() {
    vcs.createFile(p("file"), "content");
    vcs.apply();

    vcs.changeFileContent(p("file"), "new content");
    vcs.apply();

    assertEntiesContents(new String[]{"new content", "content"},
                         vcs.getEntryHistory(p("file")));
  }

  @Test
  public void testHistoryOfAnUnknownFile() {
    vcs.createFile(p("file"), "");
    vcs.apply();

    assertTrue(vcs.getEntryHistory(p("unknown file")).isEmpty());
  }

  @Test
  public void testHistoryOfDeletedFile() {
    vcs.createFile(p("file"), "content");
    vcs.apply();

    vcs.delete(p("file"));
    vcs.apply();

    // todo what should we return?
    assertTrue(vcs.getEntryHistory(p("file")).isEmpty());
  }

  @Test
  public void testDoesNotIncludeUncommittedChangesInHistory() {
    vcs.createFile(p("file"), "content");
    vcs.apply();

    vcs.changeFileContent(p("file"), "new content");

    assertEntiesContents(new String[]{"content"},
                         vcs.getEntryHistory(p("file")));
  }

  @Test
  public void testRenamingKeepsOldNameAndContent() {
    vcs.createFile(p("file"), "content");
    vcs.apply();

    vcs.rename(p("file"), "new file");
    vcs.apply();

    List<Entry> revs = vcs.getEntryHistory(p("new file"));

    assertEquals(2, revs.size());

    assertEquals(p("new file"), revs.get(0).getPath());
    assertEquals("content", revs.get(0).getContent());

    assertEquals(p("file"), revs.get(1).getPath());
    assertEquals("content", revs.get(1).getContent());
  }

  @Test
  public void testCreatingAndDeletingSameFileBeforeCommit() {
    vcs.createFile(p("file"), "");
    vcs.delete(p("file"));
    vcs.apply();

    assertFalse(vcs.hasEntry(p("file")));
  }

  @Test
  public void testDeletingAndAddingSameFileBeforeCommit() {
    vcs.createFile(p("file"), "");
    vcs.apply();

    vcs.delete(p("file"));
    vcs.createFile(p("file"), "");
    vcs.apply();

    assertTrue(vcs.hasEntry(p("file")));
  }

  @Test
  public void testAddingAndChangingSameFileBeforeCommit() {
    vcs.createFile(p("file"), "content");
    vcs.changeFileContent(p("file"), "new content");
    vcs.apply();

    assertEquals("new content", vcs.getEntry(p("file")).getContent());
  }

  @Test
  public void testDeletingFileAndCreatingNewOneWithSameName() {
    vcs.createFile(p("file"), "old");
    vcs.apply();

    vcs.delete(p("file"));
    vcs.createFile(p("file"), "new");
    vcs.apply();

    assertEntiesContents(new String[]{"new"}, vcs.getEntryHistory(p("file")));
  }

  @Test
  public void testRenamingFileAndCreatingNewOneWithSameName() {
    vcs.createFile(p("file1"), "content1");
    vcs.apply();

    vcs.rename(p("file1"), "file2");
    vcs.createFile(p("file1"), "content2");
    vcs.apply();

    assertEntiesContents(new String[]{"content1", "content1"},
                         vcs.getEntryHistory(p("file2")));

    assertEntiesContents(new String[]{"content2"},
                         vcs.getEntryHistory(p("file1")));
  }
}
