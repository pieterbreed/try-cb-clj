(defproject pieterbreed/try-cb-clj "0.1.8"
  :description "Using Couchbase from Clojure"
  :url "http://clonekim.github.io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["clojars" {:url "https://clojars.org/repo"
                             :creds :gpg}]]
  :deploy-repositories  [["releases" :clojars]
                         ["snapshots" :clojars]]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "0.5.0"]
                 [com.couchbase.client/java-client "2.7.9"]])
