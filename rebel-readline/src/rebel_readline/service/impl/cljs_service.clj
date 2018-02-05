(ns rebel-readline.service.impl.cljs-service
  (:require
   [rebel-readline.service.core :as core]
   [rebel-readline.tools.colors :as colors]
   [rebel-readline.utils :refer [log]]
   [rebel-readline.info.doc-url :as doc-url]
   [cljs-tooling.complete :as cljs-complete]
   [cljs-tooling.info :as cljs-info]   
   [cljs.analyzer.api :as ana-api]
   [cljs.analyzer :as ana]
   [cljs.env]
   [cljs.repl]
   [cljs.core]
   [clojure.string :as string]
   [clojure.tools.reader :as reader]
   [clojure.tools.reader.reader-types :as readers])
  (:import
   [java.util.regex Pattern]))

(defn format-document [{:keys [ns name type arglists doc]}]
  (when doc
    (string/join
     (System/getProperty "line.separator")
     (cond-> []
       (and ns name) (conj (str ns "/" name))
       type (conj (name type))
       arglists (conj (pr-str arglists))
       doc (conj (str "  " doc))))))

;; taken from cljs.repl
(defn- named-publics-vars
  "Gets the public vars in a namespace that are not anonymous."
  [ns]
  (->> (ana-api/ns-publics ns)
       (remove (comp :anonymous val))
       (map key)))

;; taken from cljs.repl and translated into a fn
(defn apropos
  "Given a regular expression or stringable thing, return a seq of all
  public definitions in all currently-loaded namespaces that match the
  str-or-pattern."
  [str-or-pattern]
  (let [matches? (if (instance? Pattern str-or-pattern)
                   #(re-find str-or-pattern (str %))
                   #(.contains (str %) (str str-or-pattern)))]
    (sort
     (mapcat
      (fn [ns]
        (let [ns-name (str ns)]
          (map #(symbol ns-name (str %))
               (filter matches? (named-publics-vars ns)))))
      (ana-api/all-ns)))))

(defn read-cljs-string [form-str]
  (when-not (string/blank? form-str)
    (try
      {:form (reader/read {:read-cond :allow :features #{:cljs}}
                          (readers/source-logging-push-back-reader
                           (java.io.StringReader. form-str)))}
      (catch Exception e
        {:exception (Throwable->map e)}))))

(defn eval-cljs [repl-env env form]
  (cljs.repl/evaluate-form repl-env
                           (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                           "<cljs repl>"
                           form
                           (fn [x] `(cljs.core.pr-str ~x))))

(defn data-eval
  [eval-thunk]
  (let [out-writer (java.io.StringWriter.)
        err-writer (java.io.StringWriter.)
        capture-streams (fn []
                          (.flush *out*)
                          (.flush *err*)
                          {:out (.toString out-writer)
                           :err (.toString err-writer)})]
    (binding [*out* (java.io.BufferedWriter. out-writer)
              *err* (java.io.BufferedWriter. err-writer)]
      (try
        (let [result (eval-thunk)]
          (Thread/sleep 100) ;; give printed data time to propagate
          (merge (capture-streams) {:printed-result result}))
        (catch Throwable t
          (merge (capture-streams) {:exception (Throwable->map t)}))))))

(defn create* [{:keys [repl-env] :as options}]
  (let [config-atom (atom (dissoc options :repl-env))]
    (reify
      core/Config
      (-get-config [_] @config-atom)
      (-set-config! [_ v] (reset! config-atom v))
      core/CurrentNs
      (-current-ns [_] (some-> *ns* str))
      core/Completions
      (-complete [_ word {:keys [ns]}]
        (let [options (cond-> nil
                        ns (assoc :current-ns ns))]
          (cljs-complete/completions @cljs.env/*compiler* word options)))
      core/ResolveMeta
      (-resolve-meta [self var-str]
        (cljs-info/info @cljs.env/*compiler* var-str
                        (core/-current-ns self)))
      core/Document
      (-doc [self var-str]
        (when-let [{:keys [ns name] :as info} (core/-resolve-meta self var-str)]
          (when-let [doc (format-document info)]
            (let [url (doc-url/url-for (str ns) (str name))]
              (cond-> {:doc doc}
                url (assoc :url url))))))
      core/Source
      (-source [_ var-str]
        (some->> (cljs.repl/source-fn @cljs.env/*compiler* (symbol var-str))
                 (hash-map :source)))
      core/Apropos
      (-apropos [_ var-str] (apropos var-str))
      core/ReadString
      (-read-string [_ form-str]
        (read-cljs-string form-str))
      core/Evaluation
      (-eval [_ form]
        (when repl-env
          (data-eval #(eval-cljs repl-env @cljs.env/*compiler* form))))
      (-eval-str [self form-str]
        (let [res (core/-read-string self form-str)]
          (if (contains? res :form)
              (let [form (:form res)]
                (core/-eval self form))
            res))))))

(defn create
  ([] (create nil))
  ([options]
   (create* (merge core/default-config options))))

#_(core/-get-config (create {}))
