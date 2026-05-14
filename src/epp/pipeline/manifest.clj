(ns epp.pipeline.manifest
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [epp.pipeline.json :as pipeline-json]))

(def default-chunks-dir "audio/chunks")
(def default-out-path "audio/chunks-manifest.json")
(def default-source-audio "audio/processed/leda-cosmides-normalized.mp3")
(def max-upload-bytes (* 25 1024 1024))

(defn- env-chunk-seconds []
  (Long/parseLong (or (System/getenv "CHUNK_SECONDS") "1200")))

(defn- round-3 [value]
  (Double/parseDouble (String/format java.util.Locale/US "%.3f" (object-array [(double value)]))))

(defn- display-number [value]
  (let [rounded (round-3 value)]
    (if (== rounded (Math/floor rounded))
      (long rounded)
      rounded)))

(defn- mp3-files [chunks-dir]
  (->> (fs/list-dir chunks-dir)
       (filter #(str/ends-with? (fs/file-name %) ".mp3"))
       (sort-by fs/file-name)))

(defn- probe-duration [ffprobe-bin file-path]
  (let [{:keys [out]} @(process/process [ffprobe-bin
                                         "-v" "error"
                                         "-show_entries" "format=duration"
                                         "-of" "default=noprint_wrappers=1:nokey=1"
                                         (str file-path)]
                                        {:out :string
                                         :err :inherit})]
    (Double/parseDouble (str/trim out))))

(defn build-manifest
  [{:keys [chunks-dir expected-chunk-seconds ffprobe-bin source-audio]
    :or {chunks-dir default-chunks-dir
         ffprobe-bin "ffprobe"
         source-audio default-source-audio}}]
  (let [expected-chunk-seconds (or expected-chunk-seconds (env-chunk-seconds))
        offset (volatile! 0.0)
        chunks (mapv (fn [index file-path]
                       (let [duration (probe-duration ffprobe-bin file-path)
                             start-offset @offset
                             byte-size (fs/size file-path)
                             chunk (array-map
                                    :index index
                                    :path (str file-path)
                                    :start_offset_seconds (display-number start-offset)
                                    :duration_seconds (display-number duration)
                                    :byte_size byte-size)]
                         (vswap! offset + (if (zero? duration)
                                            expected-chunk-seconds
                                            duration))
                         chunk))
                     (range)
                     (mp3-files chunks-dir))]
    (array-map
     :source_audio source-audio
     :chunk_seconds_target expected-chunk-seconds
     :max_upload_bytes max-upload-bytes
     :chunk_count (count chunks)
     :total_duration_seconds (display-number @offset)
     :chunks chunks)))

(defn build!
  ([] (build! {}))
  ([{:keys [out-path] :or {out-path default-out-path} :as opts}]
   (let [manifest (build-manifest opts)]
     (pipeline-json/write-json! out-path manifest)
     (println (str "Wrote " out-path))
     manifest)))

(defn -main [& _]
  (build!))
