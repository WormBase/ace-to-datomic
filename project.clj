(defproject wormbase/pseudoace "0.3.2-SNAPSHOT"
  :dependencies [[com.amazonaws/aws-java-sdk-dynamodb "1.9.39"
                  :exclusions [joda-time]]
                 [com.datomic/datomic-pro "0.9.5350"
                  :exclusions [joda-time]]
                 [datomic-schema "1.3.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.3"]]
  :description "ACeDB migration tools"
  :source-paths ["src"]
  :plugins [[lein-environ "1.0.0"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :license "MIT"
  :jvm-opts ["-Xmx6G"
             ;; same GC options as the transactor,
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
             ;; should minimize long pauses.
             "-Ddatomic.objectCacheMax=2500000000"
             "-Ddatomic.txTimeoutMsec=1000000"
             ;; Uncomment to prevent missing trace (HotSpot optimisation)
             ;; "-XX:-OmitStackTraceInFastThrow"
             ]
  :main ^:skip-aot pseudoace.core
  :profiles {:uberjar {:aot :all}
             :test {:resource-paths ["test/resources"]}
             :dev {:resource-paths ["test/resources"]}})
