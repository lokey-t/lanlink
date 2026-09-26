package com.lanlink.app;

import java.util.*;

/** Bounded, expiring requests. A remote retry never grants permission or replays input. */
final class PermissionNotice {
  static final long TTL = 120_000, QUIET = 30_000;

  static final class Request {
    final String peer, name;
    final Set<String> needs;
    final long created;

    Request(String peer, String name, Set<String> needs, long created) {
      this.peer = peer;
      this.name = name;
      this.needs = Collections.unmodifiableSet(new LinkedHashSet<>(needs));
      this.created = created;
    }
  }

  private Request pending;
  private final LinkedHashMap<String, Long> last = new LinkedHashMap<>();

  synchronized boolean offer(String peer, String name, String need, long now) {
    String key = peer + "/" + need;
    Long seen = last.get(key);
    if (seen != null && now - seen < QUIET) return false;
    last.put(key, now);
    while (last.size() > 64) last.remove(last.keySet().iterator().next());
    Set<String> needs = new LinkedHashSet<>();
    Request old = peek(now);
    if (old != null && old.peer.equals(peer)) needs.addAll(old.needs);
    needs.add(need);
    pending = new Request(peer, name, needs, now);
    return true;
  }

  synchronized Request peek(long now) {
    if (pending != null && now - pending.created >= TTL) pending = null;
    return pending;
  }

  synchronized void dismiss() {
    pending = null;
  }

  synchronized void removePeer(String peer) {
    if (pending != null && pending.peer.equals(peer)) pending = null;
  }

  synchronized void reset() {
    pending = null;
    last.clear();
  }
}
