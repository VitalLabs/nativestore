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
  :hooks [leiningen.cljsbuild]
  ;; lein with-profiles test cljsbuild auto test
  :cljsbuild {:builds
              [ {:id "test"
                 :source-paths ["src" "test"]
                 :compiler {:main orchestra.runner
                            :output-to "resources/test/js/testable.js"
                            :output-dir "resources/test/js/out"
                            :source-map "resources/test/js/testable.js.map"
                            :asset-path "/js/out"
                            :optimizations :whitespace
                            :recompile-dependents false
                            :pretty-print true}}]
                      :test-commands {"all" ["phantomjs" "test/phantomjs.js" "resources/test/index.html"]}})

