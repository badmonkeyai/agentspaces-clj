;; Copyright 2026 Bad Monkey, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      https://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns agentspaces.content
  "The content swarm (aspace:cap/content-swarm, srad/large-networks-bulk-data.md)
  over a `fleet/start` system with `:content true` (or a map): publish a large
  artifact as a signed manifest and serve its pieces, fetch one from every
  holder at once, share it into another group, and describe what the replica
  knows.

      (content/publish! system (io/file \"model.bin\") {:name \"model.bin\"})
      (let [fetch (content/fetch! system root)]
        (content/await fetch \"2m\")
        (content/open fetch))"
  (:refer-clojure :exclude [await])
  (:require [agentspaces.core :as core]
            [clojure.java.io :as io])
  (:import (ai.badmonkey.agentspaces.agent.content Holding Manifest)
           (ai.badmonkey.agentspaces.common.id PeerId)
           (ai.badmonkey.agentspaces.content Content Content$Description Fetch Fetch$Progress FetchOptions
                                             PublishOptions)
           (java.nio.file Path)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(defn- capability ^Content [system]
  (or (:content system)
      (throw (ex-info "no content swarm: start the peer with {:content true}" {:keys (keys system)}))))

(defn- ->size [v]
  (cond (nil? v) nil
        (number? v) (long v)
        :else (let [[_ n unit] (re-matches #"(?i)\s*(\d+)\s*(B|KiB|MiB|GiB|KB|MB|GB)?\s*" (str v))]
                (when-not n (throw (ex-info "not a byte size" {:value v})))
                (* (Long/parseLong n) (case (some-> ^String unit .toLowerCase)
                                        ("kib" "kb") 1024 ("mib" "mb") 1048576 ("gib" "gb") 1073741824 1)))))

(defn- ->publish-options ^PublishOptions [{:keys [lease piece-size name media-type attrs in-place replicate]
                                           :or {lease "7d" in-place false replicate true}}]
  (cond-> (PublishOptions/defaults)
    true (.withLease (core/lease lease))
    piece-size (.withPieceSize (int (->size piece-size)))
    name (.withName name)
    media-type (.withMediaType media-type)
    attrs (.withAttrs (into {} (map (fn [[k v]] [(clojure.core/name k) (str v)])) attrs))
    true (.withInPlace (boolean in-place))
    true (.withReplicate (boolean replicate))))

(defn manifest->map
  "A manifest as a map."
  [^Manifest m]
  {:root (.root m) :size (.size m) :piece-size (.pieceSize m) :pieces (.pieces m)
   :name (.name m) :media-type (.mediaType m) :sealing (keyword (.sealing m))
   :key-epoch (.keyEpoch m) :hashing (keyword (.hashing m)) :origin (.value (.origin m))
   :created (.created m) :attrs (into {} (map (fn [[k v]] [(keyword k) v])) (or (.attrs m) {}))
   :table-ref (.tableRef m)})

(defn publish!
  "Publishes an artifact: a `java.io.File`, a `Path`, a path string, or a
  byte array. Options: `:lease` (\"7d\"), `:piece-size` (\"1MiB\"), `:name`,
  `:media-type`, `:attrs {k v}`, `:in-place` (serve a file where it lies,
  needs an in-place backend), `:replicate` (false lists the root only through
  a collection). Returns the manifest map."
  ([system artifact] (publish! system artifact {}))
  ([system artifact opts]
   (let [c (capability system)
         configured (let [v (:content (:config system))] (when (map? v) v))
         options (->publish-options (merge (select-keys configured [:piece-size]) opts))]
     (manifest->map
      (cond
        (bytes? artifact) (.publish c ^bytes artifact options)
        (instance? Path artifact) (.publish c ^Path artifact options)
        :else (.publish c ^Path (.toPath (io/file artifact)) options))))))

(defn fetch!
  "Starts a fetch of a root from every reachable holder; returns the Java
  `Fetch` handle (complete at once when the store holds it). Options:
  `:timeout`, `:prefer [peer-id ..]`, `:avoid [peer-id ..]`."
  (^Fetch [system root] (fetch! system root {}))
  (^Fetch [system root {:keys [timeout prefer avoid]}]
   (let [options (cond-> (FetchOptions/defaults)
                   timeout (.withTimeout (core/duration timeout))
                   (seq prefer) (.prefer (into-array PeerId (map #(PeerId/of %) prefer)))
                   (seq avoid) (.avoid (into-array PeerId (map #(PeerId/of %) avoid))))]
     (.fetch (capability system) ^String root ^FetchOptions options))))

(defn progress
  "Where a fetch stands: {:pieces-verified :pieces :bytes-received :bytes-by-source :sources-active :waiting :fraction}."
  [^Fetch fetch]
  (let [^Fetch$Progress p (.progress fetch)]
    {:pieces-verified (.piecesVerified p) :pieces (.pieces p) :bytes-received (.bytesReceived p)
     :bytes-by-source (into {} (map (fn [[^PeerId k v]] [(.value k) v])) (.bytesBySource p))
     :sources-active (.sourcesActive p) :waiting (.waiting p) :fraction (.fraction p)}))

(defn await
  "Blocks until the fetch completes or `timeout` (\"30s\" default) passes; true when complete."
  ([^Fetch fetch] (await fetch "30s"))
  ([^Fetch fetch timeout] (.await fetch (core/duration timeout))))

(defn complete? [^Fetch fetch] (.complete fetch))

(defn failure
  "The failure of an ended fetch, or nil."
  [^Fetch fetch]
  (let [f (.failure fetch)] (when (.isPresent f) (.get f))))

(defn trace [^Fetch fetch] (vec (.trace fetch)))

(defn cancel! [^Fetch fetch] (.cancel fetch))

(defn open
  "A read-only `SeekableByteChannel` over the complete artifact."
  [^Fetch fetch]
  (.open fetch))

(defn read-bytes
  "The complete artifact as a byte array (for artifacts that fit in memory)."
  ^bytes [^Fetch fetch]
  (with-open [ch (.open fetch)]
    (let [buffer (java.nio.ByteBuffer/allocate (int (.size ch)))]
      (loop []
        (when (and (.hasRemaining buffer) (>= (.read ch buffer) 0))
          (recur)))
      (.array buffer))))

(defn describe
  "What the replica knows of a root without fetching: {:manifest :complete-holders
  :partial-holders :held :replicated}, or nil."
  [system root]
  (let [d (.describe (capability system) ^String root)]
    (when (.isPresent d)
      (let [^Content$Description desc (.get d)]
        {:manifest (manifest->map (.manifest desc))
         :complete-holders (.completeHolders desc)
         :partial-holders (.partialHolders desc)
         :held (.cardinality (.held desc))
         :replicated (.replicated desc)}))))

(defn list-manifests
  "Every manifest the replica shows, up to `limit` (1000)."
  ([system] (list-manifests system 1000))
  ([system limit] (mapv manifest->map (.list (capability system) (int limit)))))

(defn share!
  "The bridge: writes a manifest for a root this peer holds in full into this
  system's group, so members here fetch it from this peer without a second
  copy (the artifact came from another group's system over the same store)."
  ([system root] (share! system root {}))
  ([system root {:keys [lease] :or {lease "7d"}}]
   (manifest->map (.share (capability system) ^String root (core/lease lease)))))

(defn pin! [system root] (.pin (capability system) ^String root))

(defn unpin! [system root] (.unpin (capability system) ^String root))

(defn stats
  "The capability's counters."
  [system]
  (let [s (.stats (capability system))]
    {:fetches-active (.fetchesActive s) :roots-held (.rootsHeld s) :bytes-served (.bytesServed s)
     :bytes-received (.bytesReceived s) :wants-refused (.wantsRefused s) :pieces-struck (.piecesStruck s)}))
