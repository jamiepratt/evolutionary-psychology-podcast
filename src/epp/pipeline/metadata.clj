(ns epp.pipeline.metadata
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [epp.pipeline.json :as pipeline-json]))

(def default-xml-path "sources/episode-selected.xml")
(def default-out-path "sources/episode-selected.json")
(def source-feed "https://feed.podbean.com/epthepod/feed.xml")

(defn- parse-long-value [value]
  (when (some? value)
    (Long/parseLong (str value))))

(defn- parse-selected-item [xml-text]
  (let [wrapped (str "<root xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" "
                     "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">"
                     xml-text
                     "</root>")]
    (-> wrapped xml/parse-str :content first)))

(defn- local-name [tag]
  (name tag))

(defn- namespaced? [tag]
  (some? (namespace tag)))

(defn- child-elements [item]
  (filter map? (:content item)))

(defn- first-child [item pred]
  (first (filter pred (child-elements item))))

(defn- child-text [item pred]
  (some-> (first-child item pred)
          :content
          first
          str
          str/trim))

(defn- plain-tag? [tag-name]
  (fn [element]
    (and (= (keyword tag-name) (:tag element))
         (not (namespaced? (:tag element))))))

(defn- namespaced-tag? [tag-name]
  (fn [element]
    (and (= tag-name (local-name (:tag element)))
         (namespaced? (:tag element)))))

(defn metadata [xml-path]
  (let [item (parse-selected-item (slurp (str xml-path)))
        enclosure (:attrs (first-child item (plain-tag? "enclosure")))]
    (array-map
     :title (child-text item (plain-tag? "title"))
     :itunes_title (child-text item (namespaced-tag? "title"))
     :link (child-text item (plain-tag? "link"))
     :guid (child-text item (plain-tag? "guid"))
     :pubDate (child-text item (plain-tag? "pubDate"))
     :enclosure (array-map
                 :url (:url enclosure)
                 :length (parse-long-value (:length enclosure))
                 :type (:type enclosure))
     :duration_seconds (parse-long-value (child-text item (namespaced-tag? "duration")))
     :author (child-text item (namespaced-tag? "author"))
     :explicit (child-text item (namespaced-tag? "explicit"))
     :source_feed source-feed
     :selected_item_xml (str xml-path))))

(defn extract!
  ([] (extract! {}))
  ([{:keys [xml-path out-path]
     :or {xml-path default-xml-path
          out-path default-out-path}}]
   (let [result (metadata xml-path)]
     (pipeline-json/write-json! out-path result)
     (println (str "Wrote " out-path))
     result)))

(defn -main [& _]
  (extract!))
