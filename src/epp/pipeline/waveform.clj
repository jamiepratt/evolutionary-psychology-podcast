(ns epp.pipeline.waveform
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [epp.pipeline.json :as pipeline-json]))

(def default-config
  {:slug "leda-cosmides"
   :source-audio "web/assets/audio/leda-cosmides.mp3"
   :out-dir "web"
   :sample-rate-hz 8000
   :resolutions {:fine {:samples-per-peak 400}
                 :coarse {:samples-per-peak 4000}}})

(def allowed-options #{"slug" "sourceAudio" "outDir"})

(defn- default-process-fn [args opts]
  @(process/process args opts))

(defn- round-6 [value]
  (Double/parseDouble (String/format java.util.Locale/US "%.6f" (object-array [(double value)]))))

(defn- display-number [value]
  (let [rounded (round-6 value)]
    (if (== rounded (Math/floor rounded))
      (long rounded)
      rounded)))

(defn- parse-args [argv]
  (loop [config {}
         args (seq argv)]
    (if-not args
      config
      (let [arg (first args)]
        (when-not (str/starts-with? arg "--")
          (throw (ex-info (str "Unexpected argument: " arg) {})))
        (let [key-name (subs arg 2)
              value (second args)]
          (when (or (nil? value) (str/starts-with? value "--"))
            (throw (ex-info (str "Missing value for --" key-name) {})))
          (when-not (contains? allowed-options key-name)
            (throw (ex-info (str "Unknown option --" key-name) {})))
          (recur (assoc config
                        (case key-name
                          "sourceAudio" :source-audio
                          "outDir" :out-dir
                          (keyword key-name))
                        value)
                 (nnext args)))))))

(defn- require-source-audio! [source-audio]
  (when-not (fs/exists? source-audio)
    (throw (ex-info (str "Source audio is missing: " source-audio)
                    {:source-audio (str source-audio)}))))

(defn- run-process! [process-fn args opts description]
  (let [{:keys [exit out err]} (process-fn args opts)]
    (when-not (zero? (or exit 0))
      (throw (ex-info (str description " failed: " (str/trim (or err "")))
                      {:args args
                       :exit exit
                       :err err})))
    out))

(defn- probe-duration [process-fn source-audio]
  (let [out (run-process! process-fn
                          ["ffprobe"
                           "-v" "error"
                           "-show_entries" "format=duration"
                           "-of" "default=noprint_wrappers=1:nokey=1"
                           (str source-audio)]
                          {:out :string
                           :err :string}
                          "ffprobe")]
    (display-number (Double/parseDouble (str/trim out)))))

(defn- decode-samples [process-fn source-audio sample-rate-hz]
  (run-process! process-fn
                ["ffmpeg"
                 "-v" "error"
                 "-i" (str source-audio)
                 "-ac" "1"
                 "-ar" (str sample-rate-hz)
                 "-f" "s16le"
                 "-"]
                {:out :bytes
                 :err :string}
                "ffmpeg"))

(defn- byte-value [sample-bytes index]
  (bit-and (aget sample-bytes index) 0xff))

(defn- pcm-samples [sample-bytes]
  (let [sample-count (quot (alength sample-bytes) 2)]
    (mapv (fn [index]
            (let [offset (* index 2)
                  raw (bit-or (byte-value sample-bytes offset)
                              (bit-shift-left (byte-value sample-bytes (inc offset)) 8))
                  signed (if (>= raw 32768)
                           (- raw 65536)
                           raw)]
              (display-number (if (neg? signed)
                                (/ signed 32768.0)
                                (/ signed 32767.0)))))
          (range sample-count))))

(defn- resolution-peaks [samples sample-rate-hz {:keys [samples-per-peak]}]
  (let [windows (partition-all samples-per-peak samples)
        peaks (mapv (fn [window]
                      [(display-number (apply min window))
                       (display-number (apply max window))])
                    windows)]
    (array-map
     :samples_per_peak samples-per-peak
     :window_seconds (display-number (/ samples-per-peak sample-rate-hz))
     :peak_count (count peaks)
     :peaks peaks)))

(defn artifact [opts]
  (let [config (merge default-config opts)
        {:keys [slug source-audio sample-rate-hz resolutions process-fn]} config
        process-fn (or process-fn default-process-fn)
        duration (probe-duration process-fn source-audio)
        samples (pcm-samples (decode-samples process-fn source-audio sample-rate-hz))]
    (array-map
     :schema_version 1
     :slug slug
     :source_audio (str source-audio)
     :duration_seconds duration
     :sample_rate_hz sample-rate-hz
     :resolutions (array-map
                   :fine (resolution-peaks samples sample-rate-hz (:fine resolutions))
                   :coarse (resolution-peaks samples sample-rate-hz (:coarse resolutions))))))

(defn output-path [{:keys [out-dir slug]}]
  (fs/path out-dir "assets" "waveforms" (str slug ".json")))

(defn generate!
  ([] (generate! {}))
  ([opts]
   (let [config (merge default-config opts)
         source-audio (:source-audio config)
         out-path (output-path config)]
     (require-source-audio! source-audio)
     (let [artifact (artifact config)]
       (pipeline-json/write-json! out-path artifact)
       (println (str "Wrote " out-path))
       artifact))))

(defn -main [& args]
  (generate! (parse-args args)))
