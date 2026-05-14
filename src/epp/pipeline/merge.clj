(ns epp.pipeline.merge
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [epp.pipeline.json :as pipeline-json]))

(def default-manifest-path "audio/chunks-manifest.json")
(def default-metadata-path "sources/episode-selected.json")
(def default-speaker-map-path "audio/speaker_refs/speaker-map.json")
(def default-raw-dir "transcripts/raw_chunks")
(def default-combined-path "transcripts/combined/leda-cosmides-diarized-combined.json")
(def default-final-path "transcripts/final/leda-cosmides-transcript.md")
(def default-allowed-speaker-labels #{"DPz" "DPi" "LC"})
(def default-fallback-speaker-label-overrides (array-map :A "DPi" :B "DPi"))

(def cleanup-replacements
  [[#"\bDavid Pinsoff\b" "David Pinsof"]
   [#"\bDavid Petruzewski\b" "Dave Pietraszewski"]
   [#"\bDavid Piotr Zewski\b" "Dave Pietraszewski"]
   [#"\bDavid Pietra Zewski\b" "Dave Pietraszewski"]
   [#"\bDavid Pietrzewski\b" "Dave Pietraszewski"]
   [#"\bHugo Messier\b" "Hugo Mercier"]
   [#"\bInstitut Jean Nicot\b" "Institut Jean Nicod"]
   [#"\bInstitut Jean Nucleux\b" "Institut Jean Nicod"]
   [#"\bLita Kosmedes\b" "Leda Cosmides"]
   [#"\bLita Cosmedes\b" "Leda Cosmides"]
   [#"\bLita Cosmides\b" "Leda Cosmides"]
   [#"\bLeta Cosmedes\b" "Leda Cosmides"]
   [#"\bLeta Cosmides\b" "Leda Cosmides"]
   [#"\bLeda Cosmedes\b" "Leda Cosmides"]
   [#"\bLita\b" "Leda"]
   [#"\bLeta\b" "Leda"]
   [#"\bLado\b" "Leda"]
   [#"\bAlita\b" "Leda"]
   [#"\bJohn Toobie\b" "John Tooby"]
   [#"\bRobert Rivers\b" "Robert Trivers"]
   [#"\bConrad Lorenzen's\b" "Konrad Lorenz's"]
   [#"\bConrad Lorenz's\b" "Konrad Lorenz's"]
   [#"\bIrvin Bohr\b" "Irven DeVore"]
   [#"\bSimeon Seminar\b" "Simian Seminar"]
   [#"\bSimeon seminar\b" "Simian Seminar"]
   [#"\bDon Simons\b" "Don Symons"]
   [#"\bSimon says\b" "Symons says"]
   [#"\bsimon's\b" "Symons"]
   [#"\bsimon said\b" "Symons said"]
   [#"\bJohn Leda\b" "John and Leda"]
   [#"\bTversky and Kahneman\b" "Tversky and Kahneman"]
   [#"\bGerd Gigerenzer\b" "Gerd Gigerenzer"]])

(defn- round-3 [value]
  (Double/parseDouble
   (String/format java.util.Locale/US "%.3f" (object-array [(double value)]))))

(defn- display-number [value]
  (let [rounded (round-3 value)]
    (if (== rounded (Math/floor rounded))
      (long rounded)
      rounded)))

(defn- js-json-number [value]
  (if (and (float? value) (== value (Math/floor (double value))))
    (long value)
    value))

(defn- js-json-value [value]
  (walk/postwalk #(if (number? %) (js-json-number %) %) value))

(defn- format-time [seconds]
  (let [whole (long (max 0 (Math/floor (double seconds))))
        hours (quot whole 3600)
        minutes (quot (mod whole 3600) 60)
        seconds (mod whole 60)]
    (format "%02d:%02d:%02d" hours minutes seconds)))

(defn- clean-text [value]
  (let [text (-> (str (or value ""))
                 (str/replace #"\s+" " ")
                 (str/replace #"\s+([,.?!;:])" "$1")
                 str/trim)]
    (reduce (fn [text [pattern replacement]]
              (str/replace text pattern replacement))
            text
            cleanup-replacements)))

(defn- label-to-initials [speaker-map]
  (reduce (fn [labels speaker]
            (-> labels
                (assoc (:initials speaker) (:initials speaker))
                (assoc (:name speaker) (:initials speaker))))
          {}
          (:speakers speaker-map)))

(defn- fallback-speaker-label-overrides [speaker-map]
  (merge default-fallback-speaker-label-overrides
         (:fallback_speaker_label_overrides speaker-map)))

(defn- allowed-speaker-labels [speaker-map fallback-overrides]
  (-> (into default-allowed-speaker-labels
            (keep :initials)
            (:speakers speaker-map))
      (into (vals fallback-overrides))
      (conj "UNK")))

(defn- fallback-speaker-label [fallback-overrides label]
  (or (get fallback-overrides label)
      (get fallback-overrides (keyword label))))

(defn- correction-matches? [correction {:keys [speaker source-speaker start end]}]
  (and (or (nil? (:from_speaker correction))
           (= (:from_speaker correction) speaker))
       (or (nil? (:source_speaker correction))
           (= (:source_speaker correction) source-speaker))
       (or (nil? (:start_seconds correction))
           (<= (:start_seconds correction) start))
       (or (nil? (:end_seconds correction))
           (<= end (:end_seconds correction)))))

(defn- corrected-speaker [corrections segment speaker]
  (or (some (fn [correction]
              (when (correction-matches? correction (assoc segment :speaker speaker))
                (:speaker correction)))
            corrections)
      speaker))

(defn- speaker-label [label-map fallback-overrides allowed-labels label]
  (cond
    (or (nil? label) (= "" label)) "UNK"
    (fallback-speaker-label fallback-overrides label)
    (fallback-speaker-label fallback-overrides label)
    (contains? label-map label) (get label-map label)
    (contains? allowed-labels label) label
    :else "UNK"))

(defn- chunk-id [chunk-index]
  (format "chunk_%03d" (long chunk-index)))

(defn- raw-path [raw-dir chunk-index]
  (str (fs/path raw-dir (str (chunk-id chunk-index) ".json"))))

(defn- chunk-summary [chunk raw raw-path]
  (cond-> (array-map
           :index (:index chunk)
           :raw_path raw-path)
    (contains? raw :duration) (assoc :duration_seconds (:duration raw))
    true (assoc
          :text_length (count (:text raw))
          :segment_count (count (:segments raw))
          :usage (:usage raw nil))))

(defn- segment-records [chunk raw label-map fallback-overrides allowed-labels corrections]
  (let [chunk-index (:index chunk)
        chunk-id (chunk-id chunk-index)
        offset (:start_offset_seconds chunk)]
    (mapv (fn [segment]
            (let [source-speaker (:speaker segment nil)
                  start (display-number (+ offset (double (or (:start segment) 0))))
                  end (display-number (+ offset (double (or (:end segment) 0))))
                  speaker (speaker-label label-map fallback-overrides allowed-labels source-speaker)]
              (array-map
               :type (or (:type segment) "transcript.text.segment")
               :id (str chunk-id "_" (:id segment))
               :chunk_index chunk-index
               :start start
               :end end
               :speaker (corrected-speaker corrections
                                           {:source-speaker source-speaker
                                            :start start
                                            :end end}
                                           speaker)
               :source_speaker source-speaker
               :text (or (:text segment) ""))))
          (:segments raw))))

(defn- chunk-and-segments [raw-dir label-map fallback-overrides allowed-labels corrections chunk]
  (let [raw-path (raw-path raw-dir (:index chunk))
        raw (pipeline-json/read-json raw-path)]
    {:summary (chunk-summary chunk raw raw-path)
     :segments (segment-records chunk raw label-map fallback-overrides allowed-labels corrections)}))

(defn- combined-transcript [manifest metadata speaker-map raw-dir]
  (let [label-map (label-to-initials speaker-map)
        fallback-overrides (fallback-speaker-label-overrides speaker-map)
        allowed-labels (allowed-speaker-labels speaker-map fallback-overrides)
        corrections (:speaker_label_corrections speaker-map)
        processed (mapv #(chunk-and-segments raw-dir label-map fallback-overrides allowed-labels corrections %)
                        (:chunks manifest))
        segments (->> processed
                      (mapcat :segments)
                      (sort-by (juxt :start :end))
                      vec)]
    (array-map
     :task "transcribe"
     :metadata metadata
     :speaker_map speaker-map
     :fallback_speaker_label_overrides fallback-overrides
     :manifest default-manifest-path
     :raw_chunks (mapv :summary processed)
     :duration_seconds (:total_duration_seconds manifest)
     :text (->> segments
                (map :text)
                (str/join " ")
                (#(str/replace % #"\s+" " "))
                str/trim)
     :segments segments)))

(defn- add-turn [turns segment]
  (let [text (clean-text (:text segment))]
    (if (str/blank? text)
      turns
      (let [prior (peek turns)]
        (if (and prior
                 (= (:speaker prior) (:speaker segment))
                 (< (- (:start segment) (:end prior)) 2.5))
          (conj (pop turns)
                (assoc prior
                       :end (:end segment)
                       :text (clean-text (str (:text prior) " " text))))
          (conj turns
                (array-map
                 :speaker (:speaker segment)
                 :start (:start segment)
                 :end (:end segment)
                 :text text)))))))

(defn- turns [segments]
  (reduce add-turn [] segments))

(defn- speaker-lines [speaker-map]
  (concat (map (fn [speaker]
                 (str "- " (:initials speaker) ": " (:name speaker)))
               (:speakers speaker-map))
          ["- UNK: uncertain speaker"]))

(defn- final-markdown [metadata manifest speaker-map segments]
  (let [header (vec (concat [(str "# " (:title metadata))
                             ""
                             (str "Source: " (:link metadata))
                             (str "Published: " (:pubDate metadata))
                             (str "Duration: " (format-time (:total_duration_seconds manifest)))
                             ""
                             "## Speakers"
                             ""]
                            (speaker-lines speaker-map)
                            [""
                             "## Transcript"
                             ""]))
        lines (into header
                    (mapcat (fn [turn]
                              [(str "**[" (format-time (:start turn)) "] "
                                    (:speaker turn)
                                    ":** "
                                    (:text turn))
                               ""])
                            (turns segments)))]
    (str (str/trimr (str/join "\n" lines)) "\n")))

(defn merge!
  ([] (merge! {}))
  ([{:keys [manifest-path metadata-path speaker-map-path raw-dir combined-path final-path]
     :or {manifest-path default-manifest-path
          metadata-path default-metadata-path
          speaker-map-path default-speaker-map-path
          raw-dir default-raw-dir
          combined-path default-combined-path
          final-path default-final-path}}]
   (let [manifest (pipeline-json/read-json manifest-path)
         metadata (js-json-value (pipeline-json/read-json metadata-path))
         speaker-map (js-json-value (pipeline-json/read-json speaker-map-path))
         combined (combined-transcript manifest metadata speaker-map raw-dir)
         markdown (final-markdown metadata manifest speaker-map (:segments combined))]
     (pipeline-json/write-json! combined-path combined)
     (when-let [parent (fs/parent final-path)]
       (fs/create-dirs parent))
     (spit (str final-path) markdown)
     (println (str "Wrote " combined-path))
     (println (str "Wrote " final-path))
     {:combined combined
      :markdown markdown})))

(defn -main [& _]
  (merge!))
