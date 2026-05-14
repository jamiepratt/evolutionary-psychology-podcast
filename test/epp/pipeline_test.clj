(ns epp.pipeline-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [epp.pipeline.episode-page :as episode-page]
            [epp.pipeline.manifest :as manifest]
            [epp.pipeline.merge :as merge]
            [epp.pipeline.metadata :as metadata]
            [epp.pipeline.validation :as validation]))

(defn- read-json [path]
  (json/parse-string (slurp (str path)) true))

(defn- write-json! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (str (json/generate-string value {:pretty true}) "\n")))

(defn- generate-node-page! [{:keys [slug title transcript audio out-dir]}]
  @(process/process ["node"
                     "scripts/generate_episode_page.mjs"
                     "--slug" slug
                     "--title" title
                     "--transcript" (str transcript)
                     "--audio" (str audio)
                     "--outDir" (str out-dir)]
                    {:out :string
                     :err :string}))

(defn- normalize-html [value]
  (-> value
      (str/replace #"\r\n?" "\n")
      str/trim))

(defn- strip-script-bodies [value]
  (str/replace value #"(?s)<script[^>]*>.*?</script>" ""))

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

(defn- normalize-markdown [value]
  (-> value
      (str/replace #"\r\n?" "\n")
      str/trim))

(deftest transcript-merge-preserves-existing-node-outputs
  (let [tmp (fs/create-temp-dir)
        combined-path (fs/path tmp "leda-cosmides-diarized-combined.json")
        final-path (fs/path tmp "leda-cosmides-transcript.md")]
    (merge/merge! {:combined-path combined-path
                   :final-path final-path})
    (is (= (read-json "transcripts/combined/leda-cosmides-diarized-combined.json")
           (read-json combined-path)))
    (is (= (normalize-markdown (slurp "transcripts/final/leda-cosmides-transcript.md"))
           (normalize-markdown (slurp (str final-path)))))))

(deftest episode-page-generation-preserves-node-output-and-static-contracts
  (let [tmp (fs/create-temp-dir)
        transcript-path (fs/path tmp "combined.json")
        audio-path (fs/path tmp "fixture.mp3")
        node-out (fs/path tmp "node-web")
        bb-out (fs/path tmp "bb-web")
        slug "fixture-episode"
        title "Fixture & Episode"
        transcript {:metadata {:title "Metadata title"
                               :link "https://example.test/episode?x=1&y=2"
                               :pubDate "Thu, 14 May 2026 12:00:00 +0000"}
                    :speaker_map {:speakers [{:initials "DPz" :name "Dave Pietraszewski"}
                                             {:initials "LC" :name "Leda Cosmides"}]}
                    :duration_seconds 65.7
                    :segments [{:id "intro"
                                :speaker "DPz"
                                :start 0
                                :end 1.5
                                :text "Hello , Lita Cosmedes & friends."}
                               {:id "follow"
                                :speaker "DPz"
                                :start 2
                                :end 3
                                :text "Same turn <with markup>."}
                               {:speaker "LC"
                                :start 7.25
                                :end 9
                                :text "A new turn."}]}]
    (write-json! transcript-path transcript)
    (spit (str audio-path) "fake audio")
    (generate-node-page! {:slug slug
                          :title title
                          :transcript transcript-path
                          :audio audio-path
                          :out-dir node-out})
    (episode-page/generate! {:slug slug
                             :title title
                             :transcript transcript-path
                             :audio audio-path
                             :out-dir bb-out})
    (let [node-episode-dir (fs/path node-out "episodes" slug)
          bb-episode-dir (fs/path bb-out "episodes" slug)
          node-html (slurp (str (fs/path node-episode-dir "index.html")))
          bb-html (slurp (str (fs/path bb-episode-dir "index.html")))
          bb-static-html (strip-script-bodies bb-html)]
      (testing "semantic transcript JSON matches the Node generator"
        (is (= (read-json (fs/path node-episode-dir "transcript.json"))
               (read-json (fs/path bb-episode-dir "transcript.json")))))
      (testing "generated HTML matches the Node generator modulo line endings"
        (is (= (normalize-html node-html)
               (normalize-html bb-html))))
      (testing "audio and shared assets land in the same public web paths"
        (is (= "fake audio"
               (slurp (str (fs/path bb-out "assets" "audio" "fixture-episode.mp3")))))
        (is (fs/exists? (fs/path bb-out "assets" "player.js")))
        (is (fs/exists? (fs/path bb-out "assets" "styles.css"))))
      (testing "player and transcript contracts remain available"
        (is (str/includes? bb-html "<audio id=\"episode-audio\" controls preload=\"metadata\" src=\"../../assets/audio/fixture-episode.mp3\"></audio>"))
        (is (str/includes? bb-html "data-follow-toggle aria-pressed=\"true\""))
        (is (str/includes? bb-html "id=\"phrase-0\" data-phrase-index=\"0\" data-start=\"0\" data-end=\"1.5\" role=\"button\" tabindex=\"0\""))
        (is (str/includes? bb-html "href=\"#phrase-0\" data-seek=\"0\""))
        (is (str/includes? bb-html "id=\"phrase-2\" data-phrase-index=\"2\" data-start=\"7.25\" data-end=\"9\" role=\"button\" tabindex=\"0\"")))
      (testing "the transcript remains readable without JavaScript"
        (is (str/includes? bb-static-html "Hello, Leda Cosmides &amp; friends."))
        (is (str/includes? bb-static-html "Same turn &lt;with markup&gt;."))
        (is (str/includes? bb-static-html "A new turn."))
        (is (str/includes? bb-static-html "00:07"))))))

(deftest episode-page-cli-preserves-node-option-validation
  (testing "unexpected positional arguments"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unexpected argument: positional"
         (episode-page/-main "positional"))))
  (testing "missing option values"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Missing value for --slug"
         (episode-page/-main "--slug"))))
  (testing "unknown options"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown option --output"
         (episode-page/-main "--output" "web")))))

(deftest transcript-merge-preserves-fallback-cleanup-offset-and-grouping-behavior
  (let [tmp (fs/create-temp-dir)
        raw-dir (fs/path tmp "raw_chunks")
        manifest-path (fs/path tmp "chunks-manifest.json")
        metadata-path (fs/path tmp "episode-selected.json")
        speaker-map-path (fs/path tmp "speaker-map.json")
        combined-path (fs/path tmp "combined.json")
        final-path (fs/path tmp "final.md")]
    (write-json! manifest-path
                 {:total_duration_seconds 70.9
                  :chunks [{:index 1
                            :start_offset_seconds 60
                            :duration_seconds 10}
                           {:index 0
                            :start_offset_seconds 0
                            :duration_seconds 60}]})
    (write-json! metadata-path
                 {:title "Fixture Episode"
                  :link "https://example.test/episode"
                  :pubDate "Thu, 14 May 2026 12:00:00 +0000"})
    (write-json! speaker-map-path
                 {:speakers [{:initials "LC"
                              :name "Leda Cosmides"}
                             {:initials "DPz"
                              :name "Dave Pietraszewski"}
                             {:initials "DPi"
                              :name "David Pinsof"}]})
    (write-json! (fs/path raw-dir "chunk_000.json")
                 {:duration 60
                  :text "zero"
                  :segments [{:id "late"
                              :start 8.4567
                              :end 9.4567
                              :speaker "LC"
                              :text "Lita Cosmedes met Robert Rivers."}
                             {:id "empty"
                              :start 12
                              :end 13
                              :speaker "mystery"
                              :text "   "}]
                  :usage {:total_tokens 1}})
    (write-json! (fs/path raw-dir "chunk_001.json")
                 {:duration 10
                  :text "one"
                  :segments [{:id "named"
                              :start 0.1
                              :end 1.5
                              :speaker "David Pinsof"
                              :text "David Pinsoff says hello ,"}
                             {:id "fallback"
                              :start 2
                              :end 3
                              :speaker "B"
                              :text "and Lita replies."}
                             {:id "gap"
                              :start 7
                              :end 8
                              :text "unknown speaker"}]})
    (merge/merge! {:manifest-path manifest-path
                   :metadata-path metadata-path
                   :speaker-map-path speaker-map-path
                   :raw-dir raw-dir
                   :combined-path combined-path
                   :final-path final-path})
    (let [combined (read-json combined-path)
          markdown (slurp (str final-path))]
      (is (= 70.9 (:duration_seconds combined)))
      (is (= [{:index 1
               :raw_path (str (fs/path raw-dir "chunk_001.json"))
               :duration_seconds 10
               :text_length 3
               :segment_count 3
               :usage nil}
              {:index 0
               :raw_path (str (fs/path raw-dir "chunk_000.json"))
               :duration_seconds 60
               :text_length 4
               :segment_count 2
               :usage {:total_tokens 1}}]
             (:raw_chunks combined)))
      (is (= [{:id "chunk_000_late"
               :speaker "LC"
               :source_speaker "LC"
               :start 8.457
               :end 9.457}
              {:id "chunk_000_empty"
               :speaker "UNK"
               :source_speaker "mystery"
               :start 12
               :end 13}
              {:id "chunk_001_named"
               :speaker "DPi"
               :source_speaker "David Pinsof"
               :start 60.1
               :end 61.5}
              {:id "chunk_001_fallback"
               :speaker "DPi"
               :source_speaker "B"
               :start 62
               :end 63}
              {:id "chunk_001_gap"
               :speaker "UNK"
               :source_speaker nil
               :start 67
               :end 68}]
             (mapv #(select-keys % [:id :speaker :source_speaker :start :end])
                   (:segments combined))))
      (is (str/includes? markdown
                         "Duration: 00:01:10"))
      (is (str/includes? markdown
                         "**[00:00:08] LC:** Leda Cosmides met Robert Trivers."))
      (is (str/includes? markdown
                         "**[00:01:00] DPi:** David Pinsof says hello, and Leda replies."))
      (is (str/includes? markdown
                         "**[00:01:07] UNK:** unknown speaker")))))
