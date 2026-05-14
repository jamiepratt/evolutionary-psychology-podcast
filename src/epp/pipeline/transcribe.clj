(ns epp.pipeline.transcribe
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [epp.pipeline.json :as pipeline-json]))

(def default-manifest-path "audio/chunks-manifest.json")
(def default-speaker-map-path "audio/speaker_refs/speaker-map.json")
(def default-out-dir "transcripts/raw_chunks")
(def default-output-prefix "chunk")
(def transcription-model "gpt-4o-transcribe-diarize")
(def transcription-url "https://api.openai.com/v1/audio/transcriptions")

(defn- default-curl-fn [args]
  @(process/process (into ["curl"] args)
                    {:out :string
                     :err :string}))

(defn- api-key! [env]
  (let [api-key (get env "OPENAI_API_KEY")]
    (when-not (seq api-key)
      (throw (ex-info "OPENAI_API_KEY is not set." {})))
    api-key))

(defn- only-chunks [env]
  (when-let [value (not-empty (get env "ONLY_CHUNKS"))]
    (->> (str/split value #",")
         (map #(Long/parseLong (str/trim %)))
         set)))

(defn- output-prefix [env]
  (or (get env "OUTPUT_PREFIX") default-output-prefix))

(defn- data-url [path]
  (let [extension (str/lower-case (fs/extension path))
        mime-type (if (= "wav" extension) "audio/wav" "audio/mpeg")
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (java.nio.file.Files/readAllBytes
                                  (.toPath (io/file (str path)))))]
    (str "data:" mime-type ";base64," encoded)))

(defn- speaker-map [path]
  (if (fs/exists? path)
    (pipeline-json/read-json path)
    {:speakers []}))

(defn- speaker-references [speaker-map]
  (->> (:speakers speaker-map)
       (filter :reference_clip)
       (mapv (fn [speaker]
               {:label (:initials speaker)
                :reference (data-url (:reference_clip speaker))}))))

(defn- output-path [out-dir prefix chunk-index]
  (fs/path out-dir (format "%s_%03d.json" prefix (long chunk-index))))

(defn- request-args [api-key chunk references]
  (into ["--silent"
         "--show-error"
         "--fail-with-body"
         "--max-time"
         "3600"
         transcription-url
         "--header"
         (str "Authorization: Bearer " api-key)
         "--form"
         (str "file=@" (:path chunk) ";type=audio/mpeg")
         "--form-string"
         (str "model=" transcription-model)
         "--form-string"
         "response_format=diarized_json"
         "--form-string"
         "chunking_strategy=auto"
         "--form-string"
         "language=en"]
        (mapcat (fn [{:keys [label reference]}]
                  ["--form-string" (str "known_speaker_names[]=" label)
                   "--form-string" (str "known_speaker_references[]=" reference)])
                references)))

(defn- trim-start [value]
  (str/replace-first value #"^\s+" ""))

(defn- write-success! [path body]
  (spit (str path) (if (str/ends-with? body "\n") body (str body "\n"))))

(defn- write-error! [path body err]
  (let [error-path (str/replace (str path) #"\.json$" ".error.txt")]
    (spit error-path (trim-start (str body "\n" err)))
    error-path))

(defn transcribe!
  ([] (transcribe! {}))
  ([{:keys [manifest-path speaker-map-path out-dir env curl-fn]
     :or {manifest-path default-manifest-path
          speaker-map-path default-speaker-map-path
          out-dir default-out-dir
          env (System/getenv)
          curl-fn default-curl-fn}}]
   (let [api-key (api-key! env)
         manifest (pipeline-json/read-json manifest-path)
         selected (only-chunks env)
         prefix (output-prefix env)
         references (speaker-references (speaker-map speaker-map-path))]
     (fs/create-dirs out-dir)
     (doseq [chunk (:chunks manifest)]
       (when (or (nil? selected) (contains? selected (:index chunk)))
         (let [path (output-path out-dir prefix (:index chunk))]
           (if (fs/exists? path)
             (println (str "Skipping existing " path))
             (do
               (println (str "Transcribing " (:path chunk) " -> " path))
               (let [{:keys [exit out err]} (curl-fn (request-args api-key chunk references))
                     body (or out "")]
                 (if (zero? (or exit 0))
                   (write-success! path body)
                   (let [error-path (write-error! path body (or err ""))]
                     (throw (ex-info (str "OpenAI API error or curl failure; wrote " error-path)
                                     {:error-path error-path}))))))))))
     nil)))

(defn -main [& args]
  (transcribe! {:manifest-path (or (first args) default-manifest-path)
                :speaker-map-path (or (second args) default-speaker-map-path)}))
