(ns meta-doc.core
  "Add examples and generate more info about public vars from given namespace. Used in doc generated by Codox.

  #### Examples

  To add examples to your var call [[add-examples]] macro followed by var name and examples. See [[md5]] for result. To turn on/off generating examples bind [[*load-examples*]] variable to `true`/`false`.

  ```
  (add-examples md5
      (example \"MD5 of a string\" (md5 \"abc\"))
      (example \"MD5 of a another string\" (md5 \"Another string\"))
      (example-image \"Just an example of an image.\" \"meta_doc/md5.jpg\"))
  ```

  Additionally you can add examples directly to the metadata as a vector of example calls under the `:examples` key..

  ```
  {:examples [(example \"Description\" (+ 1 2))]}
  ```
  
  There are following types of examples:

  * [[example]] with description and code, returns map with code as string, description and actual example as function. During doc generation function is called and result is attached to the documentation. You can turn off function evaluation when second parameter is `false`
  * [[example-gen-image]] similar as above, four differences are:
      * markdown url for image is added
      * function which operates on Clojure2D `canvas` which does drawing during generation of doc.
      * md5 digest string of parameters is used as image name (png)
      * you can pass map of parameters to adjust `canvas` size, quality and background as a second argument. 
  * [[example-image]] just insert pregenerated image with description.

  
  #### Documentation alteration

  Just call [[alter-docs]] in your namespace (or provide ns) to process all examples or additional info. If you want to turn this feature on/off bind [[*alter-docs*]] dynamic variable." 
  (:require [clojure.string :as s])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:dynamic ^{:doc "Do include examples when calling [[alter-docs]]?"}
  *load-examples* true)
(def ^:dynamic ^{:doc "Do modify `:doc` metadata?"}
  *alter-docs* true)

(def ^:const ^:private ^String new-line (System/getProperty "line.separator"))
(def ^:const ^:private ^String separator (str new-line new-line))

(def ^:private escape-map {(char 0xffff) "\\0xffff"
                           (char 13) "\\r"
                           (char 10) "\\n"})

(def ^:private ^MessageDigest md5-digest (MessageDigest/getInstance "MD5"))

(defn md5
  "Return `md5` hash for given String `s`. Used to generate unique filename for example."
  [^String s] (format "%032x" (BigInteger. (int 1) (.digest md5-digest (.getBytes s)))))

;; Appenders

(defn- append-to-doc
  "Append string `s` to :doc tag in var `v`."
  [v s]
  (let [doc (:doc (meta v))]
    (alter-meta! v assoc :doc (str doc s))))

(defn- alter-info
  "Append result of function `f` to doc in var `v` and symbol `s` when given `tag` exists in metatags."
  [tag f s v]
  (when-let [tag-val (tag (meta v))]
    (append-to-doc v (f tag-val s v))))

(def ^{:private true :doc "Generate string for `:const` meta. Show value of constant."}
  alter-const-info (partial alter-info :const #(str new-line (s/escape (str "* Constant value `" %2 " = " (var-get %3) "`") escape-map))))
(def ^{:private true :doc "Generate string for `:tag` (type) meta."}
  alter-tag-info (partial alter-info :tag (fn [t & _] (str new-line "* Type: " (if (fn? t) (last (s/split (str (type t)) #"\$")) t)))))

;; Examples

(def ^:private indent-forms #{'-> 'do 'doseq 'example 'add-examples 'example-gen-image 'example-image 'let})

(defn- maybe-wrap-string
  "Wrap string into quotation marks."
  [s]
  (if (string? s) (str "\"" s "\"") s))

(defn format-forms
  "Format given form"
  ([forms indent]
   (s/join new-line (map #(str indent (if (and (seq? %)
                                               (> (count %) 2)
                                               (contains? indent-forms (first %))                                               )
                                        (let [name (str (first %))]
                                          (str "(" name " " (maybe-wrap-string (second %)) new-line
                                               (format-forms (drop 2 %)
                                                             (apply str indent (repeatedly (+ 2 (count name)) (constantly " "))))
                                               ")"))
                                        (maybe-wrap-string %))) forms)))
  ([forms] (str (format-forms forms "") new-line)))

(comment example "asdf" (do (md5 "3") (md5 "4")))
(comment format-forms (read-string "((do (a 1 2) (b 1 2)
(-> canvas
(rect 1 2 3 4))))"))

(defmacro example
  "Create example as a map. Wrap it to the function for later execution (ie. in time of generating docs). If second argument is `false` do not execute function during doc generation.

  ```
  (example \"With evaluation\" (+ 1 2))
  (example \"Without evaluation\" false (+ 1 2)
  ```
  "
  {:style/indent 1}
  [description & xample]
  (let [[call? xample] (if (first xample) [true xample] [false (next xample)])]
    `{:type :regular
      :doc ~description
      :example ~(format-forms xample)
      :value-fn ~(when call? `(fn [] ~@xample))}))

(defmacro example-gen-image
  "Create example as image. Produce markdown image url and function which operates on canvas. Unique filename for png image is generated. First parameter describes type of wrapping function (:simple, :xy-loop). Second is description. You can adjust generating canvas with map as a third parameter (optional).

  * :w - width of the resulting image (default :160)
  * :h - height of the resulting image (default :160)
  * :hints - canvas quality (default: :high)
  * :background - background color (default: 0x30426a)"
  {:style/indent :defn
   :examples [(example "Following example will be executed within canvas context." false (example-gen-image :simple "Description" (rect canvas 10 10 100 100)))
              (example "You can pass also additional parameters." false (example-gen-image :simple "Description" {:w 200 :h 200 :hints :high :background :white} false (rect 10 10 100 100)))]}
  ([draw-type description & xample]
   (let [[params xample] (if (map? (first xample))
                           [(first xample) (next xample)]
                           [{} xample])
         format ".png"
         canvas (symbol "canvas")
         sx (format-forms xample)
         fname (str (md5 (str sx description)) format) 
         value (str "![" sx "](../images/" fname " \"" description "\")")]
     `{:type :gen-image
       :doc ~description
       :example ~sx
       :draw-type ~draw-type
       :value-fn (fn [~canvas] ~@xample)
       :filename ~fname
       :value ~value})))

(defmacro example-image
  "Insert image with given filename."
  {:style/indent 1
   :examples [(example "Insert image here" false (example-image "Description" "filename.jpg"))]}
  [description fname]
  (let [value (str "![" fname "](../images/" fname " \"" description "\")")]
    `{:type :image
      :doc ~description
      :value ~value}))

(defmacro add-examples
  "Add examples as [[example]], [[example-image]] or [[example-gen-image]] to the var `v`."
  {:style/indent :defn}
  [v & examples]
  (when (and *load-examples* (seq? examples))
    `(alter-meta! (var ~v) assoc :examples (vec (conj (or (:examples (meta (var ~v))) []) ~@examples)))))

;; ns processor

(defn get-all-clojure2d-ns
  "Return all namespaces from clojure2d."
  [] 
  (->> (all-ns)
       (map ns-name)
       (filter #(re-matches #".*clojure2d.*" (str %)))
       (map the-ns)))

(defn get-metas-from-vars
  ""
  [namespace]
  (->> (ns-publics namespace)
       (vals)
       (map meta)))

(defn get-examples-from-vars
  "Return all examples from metatags"
  [namespace]
  (->> (get-metas-from-vars namespace)
       (map :examples)
       (filter (complement nil?))))

;; Generate markdown

(defmulti ^:private example-markdown
  "Generate Markdown string for every type of examples." :type)

(defn- example-format [doc s] (str separator "> " doc separator s))

(defmethod ^:private example-markdown :regular [{:keys [doc example value-fn]}]
  (let [res (if value-fn (str " ;; => " (value-fn) new-line) "")]
    (example-format doc (str "```" new-line example res "```"))))

(defmethod ^:private example-markdown :gen-image [{:keys [doc example value]}]
  (example-format doc (str "```" new-line example"```" separator value)))

(defmethod ^:private example-markdown :image [{:keys [doc value]}]
  (example-format doc value))

(defn- examples-info
  "Process all examples from `:examples` meta tag and convert to the string."
  [examples]
  (reduce #(str %1 (example-markdown %2)) "" examples))

(defmacro generate-graph-examples
  "Add graph examples to given symbols"
  [pref suffix & xs]
  (let [lst (for [x xs
                  :let [d (str "`" x "` graph")
                        n (str pref x suffix)]]
              `(add-examples ~x (example-image ~d ~n)))]
    `(do ~@lst)))

;; 

(defn alter-docs
  "Generate additional documentation for variable from given namespace (`*ns*` as default)."
  ([] (alter-docs *ns*))
  ([ns]
   (when *alter-docs*
     (let [consts (atom (sorted-map))
           category-names (assoc (:category (meta ns)) :zzzzzz "Other functions")
           categories (atom (sorted-map))]
       (doseq [[s v] (ns-publics ns)]
         (let [mv (meta v)]
           (when (and *load-examples* (contains? mv :examples))
             (append-to-doc v (str separator "#### Examples" new-line (examples-info (:examples mv))))) 
           (if (some mv [:const :tag])
             (do
               (append-to-doc v (str separator "##### Additional info" new-line))
               (alter-const-info s v)
               (alter-tag-info s v)
               (when (:const mv) (swap! consts assoc (str s) (s/escape (str (var-get v)) escape-map))))
             (when-let [cname (or (:category mv) :zzzzzz)]
               (swap! categories assoc cname (conj (cname @categories) s))))))
       (when-not (empty? @categories)
         (append-to-doc ns (str separator "  #### Categories" separator
                                (s/join new-line (map (fn [[n v]] (str "  * " (n category-names) ": " (s/join " " (sort (map #(str "[[" % "]]") v))))) @categories)))))
       (when-not (empty? @consts)
         (append-to-doc ns (str separator "  #### Constants" separator
                                (s/join new-line (map (fn [[n v]] (str "  * [[" n "]] = `" v "`")) @consts)))))))
   :done))

(defn alter-docs-in-all-clojure2d-ns
  "Alter docs for all Clojure2d namespaces."
  []
  (doseq [ns (get-all-clojure2d-ns)]
    (alter-docs ns)))

(comment alter-docs-in-all-clojure2d-ns)

(add-examples md5
  (example "MD5 of a string" (md5 "abc"))
  (example "MD5 of a another string" (md5 "Another string"))
  (example-image "Just an example of an image." "meta_doc/md5.jpg"))

(alter-docs)
