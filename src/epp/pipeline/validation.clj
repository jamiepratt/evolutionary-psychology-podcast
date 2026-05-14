(ns epp.pipeline.validation
  (:require [epp.pipeline.json :as pipeline-json]))

(def default-manifest-path "audio/chunks-manifest.json")
(def default-combined-path "transcripts/combined/leda-cosmides-diarized-combined.json")
(def default-out-path "transcripts/combined/validation-report.json")
(def default-allowed-speakers #{"DPz" "DPi" "LC" "UNK"})
(def max-upload-bytes (* 25 1024 1024))

(defn- expected-duration [manifest combined]
  (or (:expected_duration_seconds manifest)
      (get-in combined [:metadata :duration_seconds])
      7431))

(defn- duration-failures [manifest combined]
  (let [expected (expected-duration manifest combined)]
    (when (> (Math/abs (- (:total_duration_seconds manifest) expected)) 90)
      [(str "Duration " (:total_duration_seconds manifest)
            "s is not close to " expected "s.")])))

(defn- chunk-size-failures [manifest]
  (for [chunk (:chunks manifest)
        :when (>= (:byte_size chunk) max-upload-bytes)]
    (str (:path chunk) " is " (:byte_size chunk) " bytes, above the 25 MB limit.")))

(defn- monotonic-failures [combined]
  (first
   (for [[index previous current] (map vector
                                       (range 1 (count (:segments combined)))
                                       (:segments combined)
                                       (rest (:segments combined)))
         :when (< (:start current) (:start previous))]
     (str "Non-monotonic timestamp at segment " index "."))))

(defn- allowed-speakers [combined]
  (-> (into default-allowed-speakers
            (keep :initials)
            (get-in combined [:speaker_map :speakers]))
      (into (vals (:fallback_speaker_label_overrides combined)))
      (conj "UNK")))

(defn- speaker-failures [combined]
  (let [allowed (allowed-speakers combined)]
    (first
     (for [segment (:segments combined)
           :when (not (contains? allowed (:speaker segment)))]
       (str "Unexpected speaker label " (:speaker segment) ".")))))

(defn- max-chunk-bytes [chunks]
  (when (seq chunks)
    (apply max (map :byte_size chunks))))

(defn validation-report [manifest combined]
  (let [failures (vec (concat (duration-failures manifest combined)
                              (chunk-size-failures manifest)
                              (some-> (monotonic-failures combined) vector)
                              (some-> (speaker-failures combined) vector)))]
    (array-map
     :checked_at (.toString (java.time.Instant/now))
     :chunk_count (:chunk_count manifest)
     :duration_seconds (:total_duration_seconds manifest)
     :max_chunk_bytes (max-chunk-bytes (:chunks manifest))
     :segment_count (count (:segments combined))
     :speaker_labels (vec (sort (set (map :speaker (:segments combined)))))
     :failures failures)))

(defn validate!
  ([] (validate! {}))
  ([{:keys [manifest-path combined-path out-path exit-on-failure?]
     :or {manifest-path default-manifest-path
          combined-path default-combined-path
          out-path default-out-path
          exit-on-failure? true}}]
   (let [manifest (pipeline-json/read-json manifest-path)
         combined (pipeline-json/read-json combined-path)
         report (validation-report manifest combined)]
     (pipeline-json/write-json! out-path report)
     (binding [*out* (if (seq (:failures report)) *err* *out*)]
       (print (pipeline-json/json-string report)))
     (when (and exit-on-failure? (seq (:failures report)))
       (System/exit 1))
     report)))

(defn -main [& _]
  (validate!))
