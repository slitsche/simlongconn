(defproject simlongconn "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "1.6.673"]
                 [io.github.nextjournal/clerk "0.13.842"]
                 [metrics-clojure "2.10.0"]
                 ;; for testing purposes
                 [instaparse "1.4.12"]
                 [enlive "1.1.6"]]
  :main ^:skip-aot simlongconn.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             ;; :dev     {:repl-options {:init-ns user}
             ;;           :source-paths ["dev"]}
             })
