(ns epp.pipeline.json
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn read-json [path]
  (json/parse-string (slurp (str path)) true))

(declare json-value)

(defn- indent [level]
  (apply str (repeat (* 2 level) " ")))

(defn- json-key [key]
  (json/generate-string (name key)))

(defn- scalar-json [value]
  (json/generate-string value))

(defn- map-json [value level]
  (if (empty? value)
    "{}"
    (str "{\n"
         (str/join
          ",\n"
          (map (fn [[key item]]
                 (str (indent (inc level))
                      (json-key key)
                      ": "
                      (json-value item (inc level))))
               value))
         "\n"
         (indent level)
         "}")))

(defn- sequential-json [value level]
  (let [items (vec value)]
    (if (empty? items)
      "[]"
      (str "[\n"
           (str/join
            ",\n"
            (map #(str (indent (inc level)) (json-value % (inc level))) items))
           "\n"
           (indent level)
           "]"))))

(defn- json-value [value level]
  (cond
    (map? value) (map-json value level)
    (sequential? value) (sequential-json value level)
    :else (scalar-json value)))

(defn json-string [value]
  (str (json-value value 0) "\n"))

(defn write-json! [path value]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit (str path) (json-string value)))
