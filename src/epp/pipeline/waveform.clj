(ns epp.pipeline.waveform
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [epp.pipeline.json :as pipeline-json]))

(def default-bucket-seconds 0.02)
(def default-sample-rate 8000)

(defn- display-number [value]
  (let [rounded (Double/parseDouble
                 (String/format java.util.Locale/US "%.3f" (object-array [(double value)])))]
    (if (== rounded (Math/floor rounded))
      (long rounded)
      rounded)))

(defn- sample-at [^bytes samples byte-index]
  (let [lo (bit-and (aget samples byte-index) 0xff)
        hi (bit-and (aget samples (inc byte-index)) 0xff)
        unsigned (bit-or lo (bit-shift-left hi 8))]
    (if (>= unsigned 32768)
      (- unsigned 65536)
      unsigned)))

(defn- write-short-le! [out value]
  (.write out (bit-and value 0xff))
  (.write out (bit-and (bit-shift-right value 8) 0xff)))

(defn- write-peaks! [{:keys [raw-path peaks-path samples-per-bucket]}]
  (fs/create-dirs (fs/parent peaks-path))
  (let [samples (fs/read-all-bytes raw-path)
        sample-count (quot (count samples) 2)
        bucket-count (long (Math/ceil (/ (double sample-count)
                                         (double samples-per-bucket))))]
    (with-open [out (clojure.java.io/output-stream (str peaks-path))]
      (dotimes [bucket bucket-count]
        (let [start-sample (* bucket samples-per-bucket)
              end-sample (min sample-count (+ start-sample samples-per-bucket))]
          (loop [sample-index start-sample
                 min-sample 32767
                 max-sample -32768]
            (if (>= sample-index end-sample)
              (do
                (write-short-le! out min-sample)
                (write-short-le! out max-sample))
              (let [sample (sample-at samples (* sample-index 2))]
                (recur (inc sample-index)
                       (min min-sample sample)
                       (max max-sample sample))))))))
    bucket-count))

(defn generate!
  [{:keys [audio-path manifest-path peaks-path duration-seconds bucket-seconds sample-rate ffmpeg-bin]
    :or {bucket-seconds default-bucket-seconds
         sample-rate default-sample-rate
         ffmpeg-bin "ffmpeg"}}]
  (let [samples-per-bucket (max 1 (long (Math/round (* (double sample-rate)
                                                       (double bucket-seconds)))))
        raw-path (fs/create-temp-file {:prefix "epp-waveform-" :suffix ".s16le"})]
    (try
      @(process/process [ffmpeg-bin
                         "-v" "error"
                         "-y"
                         "-i" (str audio-path)
                         "-ac" "1"
                         "-ar" (str sample-rate)
                         "-f" "s16le"
                         (str raw-path)]
                        {:out :inherit
                         :err :inherit})
      (let [peak-count (write-peaks! {:raw-path raw-path
                                      :peaks-path peaks-path
                                      :samples-per-bucket samples-per-bucket})
            manifest (array-map
                      :duration_seconds (display-number
                                         (or duration-seconds
                                             (/ (* peak-count samples-per-bucket)
                                                (double sample-rate))))
                      :bucket_seconds bucket-seconds
                      :sample_rate sample-rate
                      :samples_per_bucket samples-per-bucket
                      :peak_format "s16le-min-max"
                      :bits_per_peak 16
                      :channels 1
                      :peak_count peak-count
                      :peaks (fs/file-name peaks-path))]
        (pipeline-json/write-json! manifest-path manifest)
        manifest)
      (finally
        (fs/delete-if-exists raw-path)))))
