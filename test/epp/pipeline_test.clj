(ns epp.pipeline-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [epp.pipeline.manifest :as manifest]
            [epp.pipeline.metadata :as metadata]
            [epp.pipeline.validation :as validation]))

(defn- read-json [path]
  (json/parse-string (slurp (str path)) true))

(defn- write-json! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (str (json/generate-string value {:pretty true}) "\n")))

(deftest metadata-extraction-preserves-selected-episode-shape
  (let [tmp (fs/create-temp-dir)
        out (fs/path tmp "episode-selected.json")]
    (metadata/extract! {:xml-path "sources/episode-selected.xml"
                        :out-path out})
    (is (= (read-json "sources/episode-selected.json")
           (read-json out)))))

(deftest chunk-manifest-preserves-ffprobe-offset-and-size-behavior
  (let [tmp (fs/create-temp-dir)
        chunks-dir (fs/path tmp "chunks")
        out (fs/path tmp "chunks-manifest.json")
        ffprobe (fs/path tmp "ffprobe")]
    (fs/create-dirs chunks-dir)
    (spit (str (fs/path chunks-dir "b.mp3")) "12345")
    (spit (str (fs/path chunks-dir "a.mp3")) "123")
    (spit (str (fs/path chunks-dir "ignore.txt")) "ignored")
    (spit (str ffprobe)
          (str "#!/usr/bin/env bash\n"
               "case \"${@: -1}\" in\n"
               "*/a.mp3) echo 1.2344 ;;\n"
               "*/b.mp3) echo 2 ;;\n"
               "*) echo 0 ;;\n"
               "esac\n"))
    (.setExecutable (io/file (str ffprobe)) true)
    (manifest/build! {:chunks-dir chunks-dir
                      :out-path out
                      :expected-chunk-seconds 10
                      :ffprobe-bin (str ffprobe)})
    (is (= {:source_audio "audio/processed/leda-cosmides-normalized.mp3"
            :chunk_seconds_target 10
            :max_upload_bytes 26214400
            :chunk_count 2
            :total_duration_seconds 3.234
            :chunks [{:index 0
                      :path (str (fs/path chunks-dir "a.mp3"))
                      :start_offset_seconds 0
                      :duration_seconds 1.234
                      :byte_size 3}
                     {:index 1
                      :path (str (fs/path chunks-dir "b.mp3"))
                      :start_offset_seconds 1.234
                      :duration_seconds 2
                      :byte_size 5}]}
           (read-json out)))))

(deftest validation-report-preserves-current-checks-and-output-shape
  (let [tmp (fs/create-temp-dir)
        manifest-path (fs/path tmp "chunks-manifest.json")
        combined-path (fs/path tmp "combined.json")
        report-path (fs/path tmp "validation-report.json")]
    (write-json! manifest-path
                 {:chunk_count 1
                  :total_duration_seconds 7431
                  :chunks [{:path "audio/chunks/one.mp3"
                            :byte_size 26214400}]})
    (write-json! combined-path
                 {:segments [{:start 2 :speaker "DPz"}
                             {:start 1 :speaker "BAD"}]})
    (let [report (validation/validate! {:manifest-path manifest-path
                                        :combined-path combined-path
                                        :out-path report-path
                                        :exit-on-failure? false})]
      (testing "report content"
        (is (= 1 (:chunk_count report)))
        (is (= 7431 (:duration_seconds report)))
        (is (= 26214400 (:max_chunk_bytes report)))
        (is (= 2 (:segment_count report)))
        (is (= ["BAD" "DPz"] (:speaker_labels report)))
        (is (= ["audio/chunks/one.mp3 is 26214400 bytes, above the 25 MB limit."
                "Non-monotonic timestamp at segment 1."
                "Unexpected speaker label BAD."]
               (:failures report)))
        (is (string? (:checked_at report))))
      (testing "writes the same report shape"
        (let [written (read-json report-path)]
          (is (= (dissoc report :checked_at)
                 (dissoc written :checked_at)))
          (is (string? (:checked_at written))))))))
