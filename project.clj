(defproject com.vitalreactor/nativestore "0.2.1-SNAPSHOT"
  :description "A client-side, in-memory, indexed data store."
  :url "http://github.com/vitalreactor/nativestore"
  :license {:name "MIT License"
            :url "http://github.com/vitalreactor/derive/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [im.chit/purnam.native "0.4.3"]
                 [prismatic/schema "0.2.6"]
                 [com.vitalreactor/derive "0.2.1-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.4"]]
  :plugins [[lein-cljsbuild "1.1.1"]]
  :profiles
  ;; lein with-profiles test cljsbuild auto test
  {:test {:dependencies []
          :cljsbuild {:builds
                      [ {:id "test"
                         :source-paths ["src" "test"]
                         :compiler {:output-to "target/test/testable.js"
                                    :output-dir "target/test"
                                    :optimizations :whitespace
                                    :recompile-dependents false
                                    :pretty-print true}
                         :notify-command ["phantomjs" :cljs.test/runner "target/test/testable.js"]}]
                      :test-commands {"all" ["phantomjs" :runner "target/test/testable.js"]}}}})

