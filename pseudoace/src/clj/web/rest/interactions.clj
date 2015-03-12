(ns web.rest.interactions
  (:use web.rest.object)
  (:require [cheshire.core :as json]
            [datomic.api :as d :refer (db history q touch entity)]
            [clojure.string :as str]
            [pseudoace.utils :refer [vmap vassoc]]))


(def ^:private interactor-role-map
  {:interactor-info.interactor-type/effector           :effector
   :interactor-info.interactor-type/affected           :affected
   :interactor-info.interactor-type/trans-regulator    :effector
   :interactor-info.interactor-type/cis-regulatory     :effector
   :interactor-info.interactor-type/trans-regulated    :affected
   :interactor-info.interactor-type/cis-regulated      :affected})
   

(def ^:private interactor-target
  (some-fn
   :interaction.interactor-overlapping-gene/gene
   :interaction.interactor-overlapping-cds/cds
   :interaction.interactor-overlapping-protein/protein
   :interaction.feature-interactor/feature
   ;; and more...
   ))

(def ^:private interactor-gene
  (some-fn
   (comp :gene/_corresponding-cds :gene.corresponding-cds/_cds)))

(defn- interactor-role [interactor]
  (or (interactor-role-map (first (:interactor-info/interactor-type interactor)))
      (if (interactor-gene (interactor-target interactor))
        :associated-product)
      :other))

(defn- humanize-name [ident]
  (-> (name ident)
      (str/replace #"-" " ")
      (str/capitalize)))

(defn- interaction-type [int]
  (cond
   (:interaction/physical int)
   "Physical"

   (:interaction/predicted int)
   "Predicted"

   (:interaction/regulatory int)
   (humanize-name (first (:interaction/regulatory int)))

   (:interaction/genetic int)
   (humanize-name (first (:interaction/genetic int)))

   :default
   "Unknown"))

(defn- interaction-info [int ref-obj]
  (if (or (not (:interaction/predicted int))
          (> (or (:interaction/log-likelihood-score int) 1000) 1.5))
    (let [{effectors :effector
           affecteds :affected
           others :other
           associated :associated-product}
          (group-by interactor-role (concat 
                                     (:interaction/interactor-overlapping-cds int)
                                     (:interaction/interactor-overlapping-gene int)
                                     (:interaction/interactor-overlapping-protein int)
                                     (:interaction/feature-interactor int)))
          type (interaction-type int)]
      (->>
       ;(concat
        ;; need to include associateds?

        (if (or effectors affecteds)
          (for [objh  (concat effectors others)
                obj2h affecteds
                :let [obj (interactor-target objh)
                      obj2 (interactor-target obj2h)]
                :when (or (= obj ref-obj)
                          (= obj2 ref-obj))]
            [(str (:label (pack-obj obj)) " " (:label (pack-obj obj2)))
             {:type type
              :effector obj
              :affected obj2
              :direction "Effector->Affected"}])
          (for [objh  others
                obj2h others
                :let [obj (interactor-target objh)
                      obj2 (interactor-target obj2h)]
                :when (and (not= obj obj2)
                           (or (= obj ref-obj)
                               (= obj2 ref-obj)))]
            [(str/join " " (sort [(:label (pack-obj obj)) (:label (pack-obj obj2))]))
             {:type type
              :effector obj
              :affected obj2
             :direction "non-directional"}]))  ;)
       (into {})
       (vals)))))

(defn obj-interactions [class obj]
  (let [follow #(map :interaction/_interactor-overlapping-gene
                     (:interaction.interactor-overlapping-gene/_gene %))
        ints (follow obj)]
    (->> (mapcat (fn [interaction]
                   (map vector
                        (repeat interaction)
                        (interaction-info interaction obj)))
                 ints)
         (reduce
          (fn [data [interaction {:keys [type effector affected direction]}]]
            (if (and ((some-fn :molecule/id :gene/id :rearrangement/id :feature/id) effector)
                     ((some-fn :molecule/id :gene/id :rearrangement/id :feature/id) affected))
              (let [ename (:label (pack-obj effector))
                    aname (:label (pack-obj affected))
                    phenotype (pack-obj 
                               "phenotype" 
                               (first (:interaction/interaction-phenotype interaction))) ;; warning: could be more than one.
                    key1 (str ename " " aname " " type " " (:label phenotype))   ; interactions with the same endpoints but
                    key2 (str aname " " ename " " type " " (:label phenotype))   ; a different phenotype get a separate row.
                    pack-int (pack-obj "interaction" interaction :label (str/join " : " (sort [ename aname])))
                    papers (map (partial pack-obj "paper") (:interaction/paper interaction))
                    pack-effector (pack-obj effector)
                    pack-affected (pack-obj affected)
                    data (-> data
                             ;; Check how "predicted" flags are supposed to compose
                             (assoc-in [:nodes (:id pack-effector)] (assoc pack-effector :predicted (if (= type "Predicted") 1 0)))
                             (assoc-in [:nodes (:id pack-affected)] (assoc pack-affected :predicted (if (= type "Predicted") 1 0)))
                             (assoc-in [:types type] 1)
                             (assoc-in [:ntypes (:class pack-effector)] 1)
                             (assoc-in [:ntypes (:class pack-affected)] 1)
                             ;; Slightly ugly to allow for nil phenotypes
                             (update-in [:phenotypes] vassoc (:id phenotype) phenotype))]
                (cond
                  (get-in data [:edges key1])
                  (-> data
                      (update-in [:edges key1 :interactions] conj pack-int)
                      (update-in [:edges key1 :citations] into papers))
                  
                  (get-in data [:edges key2])
                  (-> data
                      (update-in [:edges key2 :interactions] conj pack-int)
                      (update-in [:edges key2 :citations] into papers))
                  
                  :default
                  (assoc-in data [:edges key1]
                            {:interactions [pack-int]
                             :citations    (set papers)
                             :type         type
                             :effector     pack-effector
                             :affected     pack-affected
                             :direction    direction
                             :phenotype    phenotype
                             :nearby       0})))
              data))
          {}))))

(defn get-interactions [class db id]
  (let [obj (obj-get class db id)]
    (if obj
      {:status 200
       :content-type "application/json"
       :body (json/generate-string
              {:name id
               :class class
               :uri "whatevs"
               :fields
               {:name (obj-name class db id)
                :interactions
                {:description "genetic and predicted interactions"
                 :data {:edges (vals (:edges (obj-interactions class obj)))}}}}
              {:pretty true})})))

(defn get-interaction-details [class db id]
  (let [obj (obj-get class db id)]
    (if obj
      {:status 200
       :content-type "application/json"
       :body (json/generate-string
              {:name id
               :class class
               :uri "whatevs"
               :fields
               {:name (obj-name class db id)
                :data (let [{:keys [types edges ntypes nodes phenotypes]}
                            (obj-interactions class obj)]
                        {:types types
                         :edges (vals edges)
                         :ntypes ntypes
                         :nodes nodes
                         :phenotypes phenotypes
                         :showall 1})}}
              {:pretty true})})))