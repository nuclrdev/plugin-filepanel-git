# Nuclr Git Panel

Git operations presented as a mountable filesystem for Nuclr Commander. The
plugin deliberately does not try to be an IDE or a general-purpose Git GUI.

## Features

- Open a repository from any directory below its root and remember recent
  repositories.
- Browse the working tree with explicit two-character Git status markers.
- Browse staged, modified, untracked and conflicted files independently.
- Browse local/remote branches, commits, tags and stashes.
- Open any commit, branch, tag or stash as a read-only directory tree.
- Stream historical blobs without checking them out.
- Stage, unstage, discard, commit, fetch, fast-forward pull, push, create or
  checkout a branch, and create/apply/drop stashes.
- View working and staged diffs, commit details, file history and blame.
- Compare a historical file with its working-tree version or explicitly
  restore that version.
- Copy historical files and directories into the opposite local file panel.
- Browse a remote repository by URL without cloning it: files and history
  download as you browse.

## Opening the panel

Choose **Git** from the left or right panel location menu, then select
**Open Repository…**. Selecting any nested directory automatically discovers
the enclosing repository. Repositories opened successfully appear in the
recent list.

To browse a repository you have not cloned, select **Open Remote URL…** and
enter an HTTPS or SSH URL. The plugin creates an on-demand repository in
`~/.nuclr/git/mirrors` holding only the branch and tag tips, then downloads
folders, files and history as they are looked at (a partial, shallow fetch
from the promisor remote). Opening even a very large project takes about a
second:

- A folder listing downloads the folder structure of that commit once;
  sizes of files not yet downloaded are left blank.
- Viewing or copying a file downloads that file.
- **Commits** shows the newest page of history; **Load more…** at the end
  downloads the next page. The page depth adapts so a page holds about a
  hundred commits.
- **Fetch all** refreshes the branch and tag tips and restarts the history
  pages from the new HEAD.

File history and blame need the complete history: **Download full history**
replaces the on-demand repository with a `git clone --mirror` copy, which
can take minutes and gigabytes for large projects. A full mirror opened
earlier is reused instead of an on-demand repository. Fetching by commit id
needs server support, which GitHub and GitLab provide.

Remote repositories are read-only: branches, commits, tags, history trees,
quick view and copying files out work, but working-tree actions and Push are
not offered. **Remove and delete local mirror** in the context menu of the
repository list frees the disk space.

The mounted layout is:

```text
repository [branch]
├── Working Tree
├── Status
│   ├── Conflicts
│   ├── Staged
│   ├── Modified
│   └── Untracked
├── Branches
│   ├── Local
│   └── Remote
├── Commits
├── Tags
└── Stashes
```

Status markers preserve the difference between the index and working tree:

```text
 M  modified in the working tree
M   modified in the index
MM  staged and modified again
A   added to the index
 D  deleted from the working tree
??  untracked
UU  conflicted
```

Git-specific actions are available from the context menu. F2 commits, F3
views, F5 copies to the opposite panel, Shift+F3 shows a diff, Shift+F5 stages,
and Shift+F6 unstages.

## Native Git

Eclipse JGit is bundled and provides repository discovery, status, history,
blame and object/tree browsing. A native `git` executable is required for
mutating and remote operations so that Nuclr honours the user's hooks,
signing configuration, credential helpers, SSH configuration, filters and
Git LFS installation.

Native commands are executed directly without a shell. Pull is
fast-forward-only; the plugin never auto-stashes, force-pushes or resolves a
conflict implicitly.

## Plugin-only boundary

Platform SDK 4.0 does not allow one file-panel plugin to decorate another.
Consequently Git markers and Git context actions appear in this mounted Git
panel, not in the separate Local Filesystem panel. The plugin makes no SDK or
Commander changes.

The GitHub plugin remains separate and owns hosting concepts such as pull
requests, issues, Actions and releases.

## Build

Java 25 and Maven are required.

```text
mvn clean test
mvn clean package
```

The installable bundle is written to `target/filepanel-git-1.0.2.zip`.
