;; Copyright 2026 Bad Monkey, Inc.
;; Licensed under the Apache License, Version 2.0.
;;
;; The release build (tools.build). The jar is source-only, the usual shape for a
;; Clojure library: no AOT, so it runs on whatever Clojure the application brings.
;;
;;   clojure -T:build jar       ; target/agentspaces-clj-<version>.jar and its pom
;;   clojure -T:build install   ; that jar into ~/.m2
;;   clojure -T:build:deploy deploy   ; that jar to Clojars
;;
;; deploy signs in as CLOJARS_USERNAME (default albakerbma) with CLOJARS_PASSWORD, or,
;; when that is unset, the Clojars deploy token in ~/.clojure/token.
;;
;; The Java dependencies (ai.badmonkey.agentspaces/agentspaces-*) resolve from
;; Maven Central; Clojars carries only this library.
(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'ai.badmonkey.agentspaces/agentspaces-clj)
(def version "0.4.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def ^:private repo "https://github.com/badmonkeyai/agentspaces-clj")

(def ^:private pom-data
  [[:description "Idiomatic Clojure bindings for AgentSpaces: the shared, leased, replicated tuple space and its peer-to-peer fabric, driven with maps, keywords, and functions."]
   [:url repo]
   [:inceptionYear "2026"]
   [:organization
    [:name "Bad Monkey, Inc."]
    [:url "https://badmonkey.ai"]]
   [:licenses
    [:license
     [:name "Apache License, Version 2.0"]
     [:url "https://www.apache.org/licenses/LICENSE-2.0"]
     [:distribution "repo"]]]
   [:developers
    [:developer
     [:id "albaker"]
     [:name "Al Baker"]
     [:email "al@badmonkey.ai"]
     [:organization "Bad Monkey, Inc."]
     [:organizationUrl "https://badmonkey.ai"]]]
   [:issueManagement
    [:system "GitHub"]
    [:url (str repo "/issues")]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Writes the pom and builds the source jar, with LICENSE and NOTICE inside."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url repo
                      :connection "scm:git:https://github.com/badmonkeyai/agentspaces-clj.git"
                      :developerConnection "scm:git:git@github.com:badmonkeyai/agentspaces-clj.git"
                      :tag (str "v" version)}
                :pom-data pom-data})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (doseq [f ["LICENSE" "NOTICE"]]
    (b/copy-file {:src f :target (str class-dir "/META-INF/" f)}))
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "built" jar-file))

(defn install
  "Builds the jar and installs it, with its pom, into the local Maven repository."
  [_]
  (jar nil)
  (b/install {:basis @basis :lib lib :version version
              :jar-file jar-file :class-dir class-dir})
  (println "installed" lib version))

(def ^:private clojars-user "albakerbma")

(defn- token-file []
  (io/file (System/getProperty "user.home") ".clojure" "token"))

(defn- clojars-credentials
  "The Clojars username and password (a deploy token). CLOJARS_USERNAME and
  CLOJARS_PASSWORD win; otherwise the user is albakerbma and the token is read
  from ~/.clojure/token. The token is never printed."
  []
  (let [env #(not-empty (System/getenv %))
        file (token-file)
        token (when (.isFile file) (not-empty (str/trim (slurp file))))]
    {:username (or (env "CLOJARS_USERNAME") clojars-user)
     :password (or (env "CLOJARS_PASSWORD") token
                   (throw (ex-info (str "No Clojars token: set CLOJARS_PASSWORD or put a deploy token in "
                                        file)
                                   {})))}))

(defn deploy
  "Builds the jar and deploys it to Clojars. Needs the :deploy alias's
  deps-deploy on the classpath: clojure -T:build:deploy deploy. Clojars releases
  are immutable, so a version can be deployed once."
  [_]
  (let [credentials (clojars-credentials)] ; fail before building, not after
    (jar nil)
    ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
     {:installer :remote
      :artifact (b/resolve-path jar-file)
      :pom-file (b/pom-path {:lib lib :class-dir class-dir})
      :repository {"clojars" credentials}})))
