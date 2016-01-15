(defproject com.vitalreactor/nativestore "0.2.0-SNAPSHOT"
  :description "A client-side, in-memory, indexed data store."
  :url "http://github.com/vitalreactor/nativestore"
  :license {:name "MIT License"
            :url "http://github.com/vitalreactor/derive/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [im.chit/purnam.native "0.4.3"]
                 [prismatic/schema "0.2.6"]
                 [com.vitalreactor/derive "0.2.0-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.4"]]
  :plugins [[lein-cljsbuild "1.0.3"]
            [com.cemerick/austin "0.1.4"]
            [com.cemerick/clojurescript.test "0.3.1"]]
  :hooks [leiningen.cljsbuild]
  :profiles
  ;; lein with-profiles test cljsbuild auto test
  {:test {:dependencies [[com.cemerick/clojurescript.test "0.3.1"]]
          :cljsbuild {:builds
                      [ {:id "test"
                         :source-paths ["src" "test"]
                         :compiler {:output-to "target/test/testable.js"
                                    :output-dir "target/test"
                                    :optimizations :whitespace
                                    :pretty-print true
                                    :preamble ["phantomjs-shims.js"]}
                         :notify-command ["phantomjs" :cljs.test/runner "target/test/testable.js"]}]
                      :test-commands {"all" ["phantomjs" :runner
                                             "target/test/testable.js"]}}}})
                                   
