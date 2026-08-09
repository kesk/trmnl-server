(ns trmnl-server.server.aliases
  "Readable symlinks beside the id-named directories, so that `ls archive/` says something:

     archive/k9jx8v/          the real directory, named for the display's :id
     archive/Köket -> k9jx8v  a symlink named for its :name

   This exists because a web-registered display gets a *generated* :id (see
   server.devices), which is right for a permanent identifier and useless to a human
   reading a directory listing over ssh. A symlink squares that: the id keeps naming the
   thing, and the name — free to change — names a pointer that can simply be recreated.
   That's the whole reason this is safe to derive from :name when an :id isn't: a link is
   disposable, so it can follow a rename that a directory full of history can't.

   **This is the one place a device's :name reaches the filesystem**, and the rest of the
   codebase's path safety rests on the opposite arrangement — only :id is validated, and
   that single check at load is what lets every path and directory downstream skip its own
   sanitising. So `link-name` does that work here, explicitly: a name is reduced to letters,
   digits, dots, dashes and underscores, and anything that survives as \".\", \"..\", empty
   or a leading dot is dropped rather than made safe. A link is never created over anything
   that isn't already one of ours, and only symlinks are ever deleted — nothing here can
   remove a directory holding a display's archive.

   Everything is best-effort: a filesystem that won't do symlinks (or a permission problem)
   costs a WARN and nothing else, because these are a convenience for whoever is reading the
   disk and no part of serving a screen depends on them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private max-link-length
  "Longest link name we'll create. Filenames cap out around 255 bytes and a :name is free
   text; truncating keeps a pasted paragraph from being the reason a link fails to appear."
  64)

(defn- link-name
  "A display's :name reduced to a filename, or nil when nothing usable is left.

   Deliberately conservative rather than clever: every run of anything outside
   letters/digits/dot/dash/underscore becomes a single dash, so a name carrying a path
   separator can't produce a path. Leading dots and dashes are stripped too — the first
   would make the link hidden, the second makes it look like a flag to every command you'd
   then use on it — which also disposes of \".\" and \"..\" by turning them into nothing.
   Letters keep their accents (`Köket` stays `Köket`): these filesystems are UTF-8, and the
   point of the link is to be recognised."
  [label]
  (some-> label
    str/trim
    (str/replace #"[^\p{L}\p{N}._-]+" "-")
    (str/replace #"^[.\-]+" "")
    (str/replace #"[.\-]+$" "")
    not-empty
    (as-> s (subs s 0 (min (count s) max-link-length)))
    not-empty))

(defn- path ^Path [^File f] (File/.toPath f))

(defn- exists?
  "Whether anything is at this path, a broken symlink included — which is why it isn't
   (.exists f): that follows the link and calls a dangling one absent, and we'd then try to
   create it and fail."
  [^File f]
  (Files/exists (path f) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- link?
  [^File f]
  (Files/isSymbolicLink (path f)))

(defn- points-at
  "The name a symlink points to, or nil. Ours are always relative and one segment long, so
   this is the target directory's name."
  [^File f]
  (try
    (str (Files/readSymbolicLink (path f)))
    (catch Exception _ nil)))

(defn- wanted-links
  "link name -> device :id, for the displays whose name is worth a link at all. Skips one
   whose name reduces to its own id (the link would point at itself) or to *another*
   display's id (it would be claiming a real directory), and, when two displays reduce to
   the same link name, keeps the first by id and says so — the registry already allows
   duplicate names with a warning, and this is the same trade rather than a new refusal.

   Both of those comparisons are case-insensitive, because one of the two filesystems this
   runs on is. \"Hallway\" and the id `hallway` are the same file on the Mac and two
   different ones on the Pi, so a case-sensitive test would mean a pointless
   `Hallway -> hallway` link in one place and a FileAlreadyExistsException in the other."
  [devices]
  (let [ids (into #{} (map (comp str/lower-case :id)) devices)]
    (:links
     (reduce (fn [acc {:keys [id name]}]
               (let [link (link-name name)
                     key  (some-> link str/lower-case)]
                 (cond
                   (nil? link)
                   acc

                   (contains? ids key)
                   acc

                   (contains? (:taken acc) key)
                   (do (log/warn (str "Not linking " (pr-str name) " -> " id
                                   ": another display already claims that name"))
                     acc)

                   :else
                   (-> acc (assoc-in [:links link] id) (update :taken conj key)))))
       {:links {} :taken #{}} (sort-by :id devices)))))

(defn- ours?
  "Whether this entry is a link this namespace is entitled to remove: a symlink whose
   target is a plain sibling name, which is the only shape link! ever creates. A real
   directory fails the first test and an absolute or reaching-out link fails the second, so
   neither can be touched by the delete below — the one thing that must be true of this
   code is that it cannot destroy a display's archive."
  [^File f]
  (and (link? f)
    (when-let [target (points-at f)]
      (not (re-find #"[/\\]" target)))))

(defn- stale?
  "Whether one of our links no longer says the right thing. Two ways that happens: its
   display was renamed, so the link's name is no longer the one that display wants; or some
   *other* display now wants this name and the link is in the way.

   A link whose target isn't a registered display at all is deliberately **kept** — that's
   what's left after a display is removed from devices.edn by hand, and since the directory
   it points at holds that display's archive under a generated id, the link is the only
   thing on disk still explaining what it was. Keeping it can't block anything: the second
   condition removes it the moment a live display wants that name."
  [^File f ids wanted]
  (and (ours? f)
    (let [name   (.getName f)
          target (points-at f)]
      (and (not= (get wanted name) target)
        (or (contains? ids target)
          (contains? wanted name))))))

(defn- prune!
  [^File root ids wanted]
  (doseq [^File f (or (.listFiles root) [])]
    (when (stale? f ids wanted)
      (try
        (Files/delete (path f))
        (log/info (str "Removed stale directory link " (.getName f) " -> " (points-at f)))
        (catch Exception e
          (log/warn e (str "Could not remove stale directory link " (.getPath f))))))))

(defn- link!
  "Points `name` at `id` under `root`. Creates the target directory if it isn't there yet —
   a display registered a minute ago has no archive or logs until it next polls, and a link
   into thin air is worse than an empty folder. Anything already sitting at the link's own
   path is left alone: it's either the link we wanted (so there's nothing to do) or
   something we didn't make."
  [^File root name id]
  (let [link (io/file root name)]
    (when-not (exists? link)
      (try
        (.mkdirs (io/file root id))
        (Files/createSymbolicLink (path link) (Path/of id (into-array String []))
          (into-array FileAttribute []))
        (log/info (str "Linked " (.getPath link) " -> " id))
        (catch Exception e
          (log/warn e (str "Could not link " (.getPath link) " -> " id)))))))

(defn sync!
  "Makes the links under `root` match `devices` (a seq of registry entries): stale ones
   removed, missing ones created, everything else left as it is. Idempotent, so it can just
   be run at startup and after any change to the registry rather than tracking what
   changed. Cheap at a household's scale — one directory listing and a handful of stats."
  [^File root devices]
  (try
    (let [wanted (wanted-links devices)]
      ;; Only bring the root into existence when there's actually something to hang in it;
      ;; the writers create it on their own terms otherwise, and an empty archive/ on a
      ;; server that has never rendered would be a small lie.
      (when (seq wanted)
        (.mkdirs root))
      (when (.isDirectory root)
        (prune! root (into #{} (map :id) devices) wanted)
        (doseq [[name id] wanted]
          (link! root name id))))
    (catch Exception e
      (log/warn e (str "Could not sync directory links under " (.getPath root))))))
