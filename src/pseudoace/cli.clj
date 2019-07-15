(ns pseudoace.cli
  (:require
   [clj-time.core :as ct]
   [clj-time.coerce :refer [to-date]]
   [clj-time.format :as tf]
   [clojure.java.io :as io]
   [clojure.pprint :as pp :refer [pprint]]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.walk :as w]
   [datoteka.core :as fs]
   [datomic.api :as d]
   [pseudoace.aceparser :as ace]
   [pseudoace.import :refer [importer]]
   [pseudoace.liberal-txns :refer [resolve-liberal-tx]]
   [pseudoace.locatable-import :as loc-import]
   [pseudoace.model :as model]
   [pseudoace.model2schema :as model2schema]
   [pseudoace.patching :as patching]
   [pseudoace.qa :as qa]
   [pseudoace.schema-datomic :as schema-datomic]
   [pseudoace.schemata :as schemata]
   [pseudoace.ts-import :as ts-import]
   [pseudoace.utils :as utils])
  (:import
   (java.util.zip GZIPInputStream GZIPOutputStream)))

(def ^{:dynamic true} *partition-max-count* 1000)

(def ^{:dynamic true} *partition-max-text* 5000)

;; First three strings describe a short-option, long-option with optional
;; example argument description, and a description. All three are optional
;; and positional.
(def cli-options
  ;; [[ short-opt, long-opt, example], ...]
  [[nil
    "--url URL"
    (str "Datomic database URL; "
         "Example: datomic:free://localhost:4334/WS250")]
   [nil
    "--schema-filename PATH"
    (str "Name of the file for the schema view "
         "to be written; "
         "Example: \"schema250.edn\"")]
   [nil
    "--log-dir PATH"
    (str "Path to an empty directory to store the Datomic logs in; "
         "Example: /datastore/datomic/tmp/datomic/import-logs-WS250/")]
   [nil
    "--acedump-dir PATH"
    (str "Path to the directory of the desired acedump; "
         "Example: /datastore/datomic/tmp/acedata/WS250/")]
   [nil
    "--backup-file PATH"
    "Path to store the database dump."]
   [nil
    "--report-filename PATH"
    (str "Path to the file that you "
         "would like the report to be written to")]
   [nil
    "--acedb-class-report PATH"
    (str "Path to a file containing per class object counts "
         "from ACeDB.")]
   [nil
    "--models-filename PATH"
    (str "Path to the annotated models file")]
   [nil
    "--no-locatable-schema"
    "Supress locatables schema"]
   [nil
    "--no-fixups"
    "Supress schema \"fixups\" (smallace)"]
   [nil
    "--patches-ftp-url URL"
    "URL to the PATCHES directory on an ftp server."]
   [nil
    "--verify-patch"
    "Verify ACe patch is valid (convertable to EDN AND transactable against the current DB URL."]
   ["-v" "--verbose"]
   ["-f" "--force"]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn- run-delete-database
  ([url]
   (run-delete-database url false))
  ([url verbose]
   (if verbose
     (println "Deleting database: " url))
   (d/delete-database url)))

(defn- confirm-delete-db! [url]
  (println
   "Are you sure you would like to delete the database: "
   url
   " [y/n]")
  (= (.toLowerCase (read-line)) "y"))

(defn- from-datomic-conn
  [& {:keys [url conn]}]
  (let [conn (or conn (d/connect url))
        db (d/db conn)
        imp (importer conn)]
    [conn db imp]))

(defn delete-database
  "Delete the database at the given URL."
  [& {:keys [url force verbose]
      :or {force false
           verbose false}}]
  (if (or force (confirm-delete-db! url))
    (run-delete-database url verbose)
    (println "Not deleting database")))

(defn generate-schema-view
  "Export the geneated database schema to a file."
  [& {:keys [url schema-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println "Generating Datomic schema view")
    (println \tab "Creating database connection"))
  (let [conn (d/connect url)
        schemas (schema-datomic/schema-from-db (d/db conn))]
    (utils/with-outfile schema-filename
      (-> schemas vec pprint))
    (when verbose
      (print "Releasing db connection ... "))
    (d/release conn))
  (when verbose
    (println "done.")))

(defn generate-schema
  "Generate the database schema from the annotated ACeDB models."
  [& {:keys [models-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println \tab "Generating Schema")
    (println \tab "Read in annotated ACeDB models")
    (println \tab (str "Making the datomic schema from the acedb "
                       "annotated models")))
  (if-let [stream (-> models-filename
                      io/file
                      io/input-stream)]
    (-> stream
        io/reader
        model/parse-models
        model2schema/models->schema)))

(defn load-schema
  "Load the schema for the database."
  ([url models-filename]
   (load-schema url models-filename false false false))
  ([url models-filename verbose]
   (load-schema url models-filename false false verbose))
  ([url models-filename no-locatables no-fixups verbose]
   (when verbose
     (println (str/join " " ["Loading Schema into:" url]))
     (println \tab "Creating database connection"))
   (let [conn (d/connect url)
         main-schema (generate-schema
                      :models-filename models-filename
                      :verbose verbose)]
     (schemata/install conn
                       main-schema
                       :no-locatables no-locatables
                       :no-fixups no-fixups)
     (d/release conn))))

(defn create-database
  "Create a Datomic database from a schema generated
  and an annotated ACeDB models file."
  [& {:keys [url
             models-filename
             no-locatable-schema
             no-fixups
             verbose]
      :or {no-locatable-schema false
           no-fixups false
           verbose false}}]
  (when verbose
    (println "Creating Database")
    (if no-locatable-schema
      (println "Locatable schema will not be applied"))
    (if no-fixups
      (println "Fixups will not be applied")))
  (d/create-database url)
  (load-schema url
               models-filename
               no-locatable-schema
               no-fixups
               verbose)
  true)

(defn uri-to-helper-uri [uri]
  (str/join "-" [uri "helper"]))

(defn create-helper-database
  "Create a helper Datomic database from a schema."
  [& {:keys [url models-filename verbose]
      :or {verbose false}}]
  (if verbose
    (println "Creating Helper Database"))
  (generate-schema
   :models-filename models-filename
   :verbose verbose)
  (let [helper-uri (uri-to-helper-uri url)]
    (d/create-database helper-uri)
    (load-schema helper-uri models-filename verbose)))

(defn directory-walk [directory pattern]
  (doall (filter #(re-matches pattern (fs/name %))
                 (fs/list-dir directory))))

(defn get-ace-files [directory]
  (map fs/file (directory-walk directory #".*\.ace.gz")))

(defn get-sorted-edn-log-files
  "Sort EDN log files for import.

  Sorting:

    * lexographically by name
    * filter files having names which match a timestamp greater or equal
      to latest transaction date.

  Returns a sequence of sorted files (earliest date first)."
  [log-dir db latest-tx-dt]
  (let [tx-date (apply
                 ct/date-time
                 (map #(% latest-tx-dt) [ct/year ct/month ct/day]))
        day-before-tx-dt (ct/minus tx-date (ct/days 1))
        edn-files (filter #(str/ends-with? (fs/name %) ".edn.sort.gz")
                          (fs/list-dir log-dir))
        edn-file-map (reduce
                      (fn [m f]
                        (assoc m (fs/name f) f))
                      {}
                      edn-files)
        filenames (utils/filter-by-date
                   (keys edn-file-map)
                   day-before-tx-dt
                   ct/after?)]
  (for [filename filenames]
    (edn-file-map filename))))

(defn apply-patch
  "Apply an ACe patch to the Datomic database specified by url or connection from a local file path."
  [& {:keys [url patch-path verbose verify-patch conn]}]
  (let [[conn* db imp] (from-datomic-conn :url url :conn conn)
        patches-dir (fs/parent patch-path)
        edn-patch-file (do
                         (when verbose
                           (print "Converting ACe patch" (fs/name patch-path) "to EDN ... ")
                           (let [x (patching/convert-patch imp db patch-path patches-dir)]
                             (println "done.")
                             x)))]
    (when verbose
      (print "Applying patch:" (-> edn-patch-file str fs/name) "... "))
    (ts-import/play-logfile conn*
                            edn-patch-file
                            *partition-max-count*
                            *partition-max-text*
                            :use-with? verify-patch))
    (println "done."))

(defn apply-patches
  "Fetch and apply ACeDB patches to the datomic database from a given FTP release URL."
  [& {:keys [url patches-ftp-url verbose]
      :or {verbose false}}]
  (println url patches-ftp-url verbose)
  (let [[conn db imp] (from-datomic-conn :url url)
        rc (patching/find-release-code patches-ftp-url)]
    (if-let [patches-path (patching/fetch-ace-patches patches-ftp-url verbose)]
      (do
        (doseq [patch-file (some->> (fs/list-dir patches-path)
                                    (remove nil?)
                                    (sequence patching/list-ace-files))]
          (apply-patch :url url
                       :conn conn
                       :patch-path patch-file
                       :verbose verbose)))
      (println (str "No patches detected for " rc " from " patches-ftp-url)))))

(defn acedump-file-to-datalog
  ([imp file log-dir]
   (acedump-file-to-datalog imp file log-dir false))
  ([imp file log-dir verbose]
   (if (not verbose)
     (println \tab "Converting" file))
   ;; then pull out objects from the pipeline in chunks of 20 objects.
   ;; Larger block size may be faster if you have plenty of memory
   (doseq [blk (partition-all 20 (patching/read-ace-patch))]
     (ts-import/split-logs-to-dir imp blk log-dir))))

(def helper-filename "helper.edn.gz")
(def helper-folder-name "helper")

(defn helper-dest-file [log-dir]
  (io/file log-dir helper-folder-name helper-filename))

(defn move-helper-log-file
  ([log-dir]
   (move-helper-log-file log-dir false))
  ([log-dir verbose]
   (let [dest-file (helper-dest-file log-dir)
         helper-dir (io/file (.getParent dest-file))]
     (if-not (.exists helper-dir)
       (.mkdir helper-dir))
     (let [source (io/file log-dir helper-filename)]
       (when (.exists source)
         (if verbose
           (println \tab "Moving helper log file"))
         (io/copy source dest-file)
         (io/delete-file source))))))

(defn acedump-to-edn-logs
  "Create the EDN log files."
  [& {:keys [url log-dir acedump-dir verbose]
      :or {verbose false}}]
  (when verbose
    (println "Converting ACeDump to Datomic Log")
    (println "Creating database connection"))
  (let [conn (d/connect url)
        imp (importer conn)
        directory (io/file log-dir)
        files (get-ace-files acedump-dir)]
    (doseq [file files]
      (acedump-file-to-datalog imp file directory verbose))
    (move-helper-log-file log-dir verbose)
    (d/release conn)))

(defn import-logs
  "Import the sorted EDN log files."
  [& {:keys [url log-dir partition-max-count partition-max-text verbose]
      :or {verbose false
           partition-max-count *partition-max-count*
           partition-max-text *partition-max-text*}}]
  (if verbose
    (println "Importing logs into datomic database"
             url
             "from log files in"
             log-dir))
  (let [conn (d/connect url)
        db (d/db conn)
        latest-tx-dt (ts-import/latest-transaction-date db)
        log-files (get-sorted-edn-log-files log-dir db latest-tx-dt)]
    (if verbose
      (println "Importing" (count log-files) "log files"))
    (doseq [file log-files]
      (if verbose
        (println \tab "importing: " (.getName file)))
      (ts-import/play-logfile
       conn
       (GZIPInputStream. (io/input-stream file))
       partition-max-count
       partition-max-text))
    (d/release conn)))

(defn excise-tmp-data
  "Remove all the temporary data created during processing."
  [& {:keys [url]}]
  (let [conn (d/connect url)]
    (d/transact
     conn
     [{:db/id (d/tempid :db.part/user)
       :db/excise :importer/temp}])
    (d/request-index conn)
    (->> conn d/db d/basis-t (d/sync-index conn) deref)
    (d/gc-storage conn (to-date (ct/now)))))

(defn run-test-query
  "Perform tests on the generated database."
  [& {:keys [url verbose]
      :or {verbose false}}]
  (let [conn (d/connect url)
        n-expected 1
        datalog-query '[:find ?c
                        :in $
                        :where
                        [?c :gene/id "WBGene00018635"]]
        results (d/q datalog-query (d/db conn))
        n-results (count results)]
    (when verbose
      (println "Testing datomic data, expecting exactly"
               n-expected
               "result")
      (println "Datalog query:" datalog-query)
      (print "Results: ")
      (pprint results))
    (if-let [success (t/is (= n-results 1))]
      (println "OK")
      (println "Failed to find record matching " datalog-query))
    (d/release conn)))

(defn import-helper-edn-logs
  "Import the helper log files."
  [& {:keys
      [url log-dir partition-max-count partition-max-text verbose]
      :or {partition-max-count *partition-max-count*
           partition-max-text *partition-max-text*
           verbose false}}]
  (if verbose
    (println "Importing helper log into helper database"))
  (let [helper-uri (uri-to-helper-uri url)
        helper-connection (d/connect helper-uri)]
    (binding [ts-import/*suppress-timestamps* true]
      (ts-import/play-logfile
       helper-connection
       (GZIPInputStream.
        (io/input-stream (helper-dest-file log-dir)))
       partition-max-count
       partition-max-text))
    (if verbose
      (println \tab "Releasing helper database connection"))
    (d/release helper-connection)))

(defn helper-file-to-datalog [helper-db file log-dir verbose]
  (if (utils/not-nil? verbose)
    (println \tab "Adding extra data from: " file))
  ;; then pull out objects from the pipeline in chunks of 20 objects.
  ;; Larger block size may be faster if
  ;; you have plenty of memory.
  (doseq [blk (partition-all 20 (patching/read-ace-patch file))]
    (loc-import/split-locatables-to-dir helper-db blk log-dir)))

(defn run-locatables-importer-for-helper
  ([url acedump-dir]
   (run-locatables-importer-for-helper url acedump-dir :verbose false))
  ([url log-dir acedump-dir verbose]
   (if verbose
     (println (str "Importing logs with loactables "
                   "importer into helper database")))
   (let [helper-uri (uri-to-helper-uri url)
         helper-connection (d/connect helper-uri)
         helper-db (d/db helper-connection)
         files (get-ace-files acedump-dir)]
     (doseq [file files]
       (helper-file-to-datalog helper-db file log-dir verbose))
     (if verbose
       (println \tab "Releasing helper database connection"))
     (d/release helper-connection))))

(defn delete-helper-database
  "Delete the \"helper\" database."
  [& {:keys [url verbose]}]
  (if verbose
    (println "Deleting helper database"))
  (let [helper_uri (uri-to-helper-uri url)]
    (d/delete-database helper_uri)))

(defn prepare-import
  "Setup the database schema and parse the acedb files for later sorting."
  [& {:keys [url
             models-filename
             log-dir
             acedump-dir
             schema-filename
             no-locatable-schema
             no-fixups
             verbose]
      :or {schema-filename nil
           verbose false}}]
  (create-database :url url
                   :models-filename models-filename
                   :no-locatable-schema no-locatable-schema
                   :no-fixups no-fixups
                   :verbose verbose)
  (acedump-to-edn-logs :url url
                       :log-dir log-dir
                       :acedump-dir acedump-dir
                       :verbose verbose)
  (if-not (nil? schema-filename)
    (generate-schema-view
     :url url
     :schema-filename schema-filename
     :verbose verbose)))

(defn list-databases
  "List all databases."
  [& {:keys [url]}]
  (doseq [database-name (d/get-database-names url)]
    (println database-name)))

(defn write-report [filename db]
  (let [elements-attributes (sort (d/q '[:find [?ident ...]
                                         :where [_ :db/ident ?ident]]
                                       db))]
    (with-open [wrtr (io/writer filename)]
      (binding [*out* wrtr]
        (print "element" "\t" "attribute" "\t" "count")
        (doseq [element-attribute elements-attributes]
          (let [element (namespace element-attribute)
                attribute (name element-attribute)
                expression [:find '(count ?eid) '.
                            :where ['?eid element-attribute]]
                entity-name (d/q expression db)
                line (str element "\t" attribute "\t" entity-name)]
            (println line)))))))

(defn generate-report
  "Generate a summary report of database content."
  [& {:keys [url report-filename acedb-class-report verbose]
      :or {verbose false}}]
  (if verbose
    (println "Generating Datomic database report"))
  (let [conn (d/connect url)
        db (d/db conn)]
    (try
      (qa/report-import-stats db acedb-class-report report-filename
                              :verbose verbose)
      (finally
        (d/release conn)))
    nil))

(def cli-actions [#'acedump-to-edn-logs
                  #'apply-patches
                  #'create-database
                  #'create-helper-database
                  #'delete-database
                  #'excise-tmp-data
                  #'generate-report
                  #'generate-schema-view
                  #'import-helper-edn-logs
                  #'import-logs
                  #'list-databases
                  #'prepare-import
                  #'run-test-query])

(def cli-action-metas (map meta cli-actions))

(def cli-action-map (zipmap
                     (map #(str (:name %)) cli-action-metas)
                     cli-actions))

(def cli-doc-map (into
                  {}
                  (for [m cli-action-metas]
                    {(#(str (:name %)) m) (:doc m)})))

(def ^:private space-join (partial str/join "  "))

(defn- collapse-space
  "Remove occruances of multiple spaces in `s` with a single space."
  [s]
  (str/replace s #"\s{2,}" " "))

(defn- required-kwds
  "Return the required keyword arguments to `func-ref`.

  `func-ref` should be a reference to function."
  [func-ref]
  (let [func-info (meta func-ref)
        arglist (-> func-info :arglists flatten)
        kwds (last arglist)
        opt (apply sorted-set (-> kwds :or keys))
        req (apply sorted-set (:keys kwds))]
    (map keyword (set/difference req opt))))

(defn invoke-action
  "Invoke `action-name` with the options supplied."
  [action options args]
  (let [supplied-opts (set (keys options))
        required-opts (set (required-kwds action))
        missing (set/difference required-opts supplied-opts)]
    (if (empty? missing)
      (do
        (println "OPTS:" (pr-str options))
        (println "ARGS:" (pr-str args))
        (apply action (flatten (into '() options))))
      (println
       "Missing required options:"
       (str/join " --" (conj (map name missing) nil))))))

(defn usage
  "Display command usage to the user."
  [options-summary]
  (let [action-names (keys cli-doc-map)
        action-docs (vals cli-doc-map)
        doc-width-left (+ 10 (apply max (map count action-docs)))
        action-width-right (+ 10 (apply max (map count action-names)))
        line-template (str "%-"
                           action-width-right
                           "s%-"
                           doc-width-left
                           "s")]
    (str/join
     \newline
     (concat
      [(str "This is tool for importing data from ACeDB "
            "into a Datomic database")
       ""
       "Usage: pseudoace [options] action"
       ""
       "Options:"
       options-summary
       ""
       (str "Actions: (required options for each action "
            "are provided in square brackets)")]
      (for [action-name (sort action-names)
            :let [doc-string (cli-doc-map action-name)]]
        (let [usage-doc (some-> doc-string
                                str/split-lines
                                space-join
                                collapse-space)]
          (format line-template action-name usage-doc)))))))

(defn -main [& args]
  (let [{:keys [options
                arguments
                errors
                summary]} (parse-opts args cli-options)]
    (if errors
      (exit 1 (error-msg errors)))
    (if (:help options)
      (exit 0 (usage summary)))
    (if-let [action-name (first arguments)]
      (if-let [action (get cli-action-map action-name)]
        (do
          (invoke-action action options (rest arguments))
          (exit 0 "OK"))
        (exit 1 (str "Unknown argument(s): " action-name)))
      (exit 0 (usage summary)))))
