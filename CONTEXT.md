# LeNews

A FreshRSS client for Android, for one account and a lot of articles. This
glossary is the language for articles and their state; it holds no
implementation detail.

## Language

**Article**:
One entry in a feed, as FreshRSS delivers it. Its identity is the number
FreshRSS gives it, which never changes and is never shared by two articles.
_Avoid_: item, entry, post

**Feed**:
A source of articles the FreshRSS account is subscribed to.
_Avoid_: subscription, source

**Folder**:
A FreshRSS category grouping feeds. Flat; a feed is in at most one.
_Avoid_: category, tag

**Unread**:
The state of an article nobody has acted on yet. The timeline shows these by
default.
_Avoid_: new

**Read**:
The state of an article that has been acted on, by any route: opened,
swiped, marked read in bulk, or read elsewhere and learned at sync.
_Avoid_: dismissed, seen, done

**Becoming read**:
The transition from Unread to Read. Every occurrence is dated and enters the
History, whatever the route.
_Avoid_: dismiss, mark as read (as a noun)

**History**:
Every article that became read within the horizon, in the order it became
read, newest first. The way back to an article that was swiped away.
_Avoid_: read list, archive

**Horizon**:
The age past which a read article is no longer kept on the phone, whatever
FreshRSS still has. Thirty days.
_Avoid_: retention, TTL

**Starred**:
An article the user has marked to keep. Starred articles are kept regardless
of the horizon and of what FreshRSS returns.
_Avoid_: favourite, saved

**Sync**:
One exchange with FreshRSS: push the pending changes, then pull what has
changed since the cursor. Repeating a sync must change nothing.
_Avoid_: refresh, update, fetch

**Pending change**:
A read or starred decision made on the phone that FreshRSS has not yet been
told. The phone's decision wins over the server's answer until it is uploaded.
_Avoid_: queue, dirty flag, state change

**Cursor**:
The moment of the last successful sync, from which the next one asks FreshRSS
for what changed.
_Avoid_: lastModified, timestamp

**Mirror**:
The rule that the phone holds what FreshRSS holds, no more: an article
FreshRSS no longer returns is dropped locally, unless it is starred or within
the horizon.
_Avoid_: cache, purge, cleanup

**Duplicate**:
Two local rows for one article. A defect, never a state.
