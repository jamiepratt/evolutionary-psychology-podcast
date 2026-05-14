(ns epp.pipeline.episode-page
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [epp.pipeline.json :as pipeline-json]))

(def default-config
  {:slug "leda-cosmides"
   :title "Founding Evolutionary Psychology with Leda Cosmides"
   :transcript "transcripts/combined/leda-cosmides-diarized-combined.json"
   :audio "audio/processed/leda-cosmides-normalized.mp3"
   :out-dir "web"})

(def speaker-fallbacks
  (array-map
   :DPz "Dave Pietraszewski"
   :DPi "David Pinsof"
   :LC "Leda Cosmides"
   :UNK "Uncertain speaker"))

(def cleanup-replacements
  [[#"\bDavid Pinsoff\b" "David Pinsof"]
   [#"\bDavid Piotr Zewski\b" "Dave Pietraszewski"]
   [#"\bDavid Pietra Zewski\b" "Dave Pietraszewski"]
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

(def allowed-options #{"slug" "title" "transcript" "audio" "outDir"})

(defn- parse-args [argv]
  (loop [config default-config
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
                        (if (= key-name "outDir")
                          :out-dir
                          (keyword key-name))
                        value)
                 (nnext args)))))))

(defn- clean-text [value]
  (let [text (-> (str (or value ""))
                 (str/replace #"\s+" " ")
                 (str/replace #"\s+([,.?!;:])" "$1")
                 str/trim)]
    (reduce (fn [text [pattern replacement]]
              (str/replace text pattern replacement))
            text
            cleanup-replacements)))

(defn- fmt-clock [seconds]
  (let [whole (long (max 0 (Math/floor (double seconds))))
        hours (quot whole 3600)
        minutes (quot (mod whole 3600) 60)
        seconds (mod whole 60)]
    (if (pos? hours)
      (format "%02d:%02d:%02d" hours minutes seconds)
      (format "%02d:%02d" minutes seconds))))

(defn- escape-html [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- speaker-names [transcript]
  (reduce (fn [names speaker]
            (if (and (:initials speaker) (:name speaker))
              (assoc names (keyword (:initials speaker)) (:name speaker))
              names))
          speaker-fallbacks
          (get-in transcript [:speaker_map :speakers] [])))

(defn- normalize-segments [transcript]
  (->> (:segments transcript)
       (map-indexed
        (fn [index segment]
          (array-map
           :id (or (:id segment) (str "segment_" index))
           :speaker (or (:speaker segment) "UNK")
           :start (:start segment)
           :end (:end segment)
           :text (clean-text (:text segment)))))
       (filterv #(not (str/blank? (:text %))))))

(defn- validate-segments [segments]
  (let [failures (reduce
                  (fn [failures [index segment]]
                    (cond-> failures
                      (or (not (number? (:start segment)))
                          (not (number? (:end segment))))
                      (conj (str "Segment " (:id segment) " has invalid timing."))

                      (and (number? (:start segment))
                           (number? (:end segment))
                           (< (:end segment) (:start segment)))
                      (conj (str "Segment " (:id segment) " ends before it starts."))

                      (or (str/blank? (:text segment))
                          (str/blank? (:speaker segment)))
                      (conj (str "Segment " (:id segment) " is missing text or speaker."))

                      (and (pos? index)
                           (number? (:start segment))
                           (number? (:start (nth segments (dec index))))
                           (< (:start segment) (:start (nth segments (dec index)))))
                      (conj (str "Segment " (:id segment) " starts before the previous segment."))))
                  []
                  (map-indexed vector segments))]
    (when (seq failures)
      (throw (ex-info (str "Transcript validation failed:\n"
                           (str/join "\n" failures))
                      {:failures failures})))))

(defn- add-turn [turns segment]
  (let [prior (peek turns)]
    (if (and prior
             (= (:speaker prior) (:speaker segment))
             (< (- (:start segment) (:end prior)) 2.5))
      (conj (pop turns)
            (assoc prior
                   :end (:end segment)
                   :phrases (conj (:phrases prior) segment)))
      (conj turns
            (array-map
             :speaker (:speaker segment)
             :start (:start segment)
             :end (:end segment)
             :phrases [segment])))))

(defn- build-turns [segments]
  (reduce add-turn [] segments))

(defn- render-phrase [segment index]
  (str "<span class=\"phrase\" id=\"phrase-" index
       "\" data-phrase-index=\"" index
       "\" data-start=\"" (:start segment)
       "\" data-end=\"" (:end segment)
       "\" role=\"button\" tabindex=\"0\">"
       (escape-html (:text segment))
       "</span>"))

(defn- render-turn [turn turn-index names phrase-offset]
  (let [speaker-name (get names (keyword (:speaker turn)) (:speaker turn))
        phrases (->> (:phrases turn)
                     (map-indexed (fn [offset segment]
                                    (render-phrase segment (+ phrase-offset offset))))
                     (str/join " "))]
    (str "<article class=\"turn\" id=\"turn-" turn-index
         "\" data-speaker=\"" (escape-html (:speaker turn)) "\">\n"
         "  <header class=\"turn-meta\">\n"
         "    <a class=\"timestamp\" href=\"#phrase-" phrase-offset
         "\" data-seek=\"" (:start turn) "\">" (fmt-clock (:start turn)) "</a>\n"
         "    <span class=\"speaker-code\">" (escape-html (:speaker turn)) "</span>\n"
         "    <span class=\"speaker-name\">" (escape-html speaker-name) "</span>\n"
         "  </header>\n"
         "  <p>" phrases "</p>\n"
         "</article>")))

(defn- rendered-turns [turns names]
  (:html
   (reduce (fn [{:keys [offset html]} [turn-index turn]]
             {:offset (+ offset (count (:phrases turn)))
              :html (conj html (render-turn turn turn-index names offset))})
           {:offset 0 :html []}
           (map-indexed vector turns))))

(defn- json-script-data [public-transcript]
  (str/replace (json/generate-string public-transcript) "<" "\\u003c"))

(defn- render-html [{:keys [config transcript public-transcript turns names audio-href]}]
  (let [title (or (:title config)
                  (get-in transcript [:metadata :title])
                  "Episode Transcript")
        source-link (get-in transcript [:metadata :link])
        published (get-in transcript [:metadata :pubDate])
        duration (:duration_seconds transcript)
        rendered-turns (str/join "\n" (rendered-turns turns names))]
    (str "<!doctype html>\n"
         "<html lang=\"en\">\n"
         "<head>\n"
         "  <meta charset=\"utf-8\">\n"
         "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "  <title>" (escape-html title) "</title>\n"
         "  <link rel=\"stylesheet\" href=\"../../assets/styles.css\">\n"
         "</head>\n"
         "<body>\n"
         "  <header class=\"player-shell\">\n"
         "    <div class=\"episode-kicker\">Evolutionary Psychology Podcast</div>\n"
         "    <div class=\"episode-heading\">\n"
         "      <h1>" (escape-html title) "</h1>\n"
         "      <div class=\"episode-meta\">\n"
         "        " (if published (str "<span>" (escape-html published) "</span>") "") "\n"
         "        " (if (number? duration) (str "<span>" (fmt-clock duration) "</span>") "") "\n"
         "        " (if source-link (str "<a href=\"" (escape-html source-link) "\">Source episode</a>") "") "\n"
         "      </div>\n"
         "    </div>\n"
         "    <div class=\"audio-row\">\n"
         "      <audio id=\"episode-audio\" controls preload=\"metadata\" src=\""
         (escape-html audio-href)
         "\"></audio>\n"
         "      <button class=\"follow-button\" type=\"button\" data-follow-toggle aria-pressed=\"true\">Following transcript</button>\n"
         "    </div>\n"
         "  </header>\n"
         "\n"
         "  <main class=\"page\">\n"
         "    <section class=\"transcript-shell\" aria-label=\"Transcript\">\n"
         "      " rendered-turns "\n"
         "    </section>\n"
         "  </main>\n"
         "\n"
         "  <script id=\"transcript-data\" type=\"application/json\">"
         (json-script-data public-transcript)
         "</script>\n"
         "  <script src=\"../../assets/player.js\"></script>\n"
         "</body>\n"
         "</html>\n")))

(defn- copy-shared-assets! [out-dir]
  (let [assets-dir (fs/path out-dir "assets")]
    (fs/create-dirs assets-dir)
    (fs/copy (fs/path "web_assets" "styles.css")
             (fs/path assets-dir "styles.css")
             {:replace-existing true})
    (let [player-target (fs/path assets-dir "player.js")
          built-player (fs/path "web" "assets" "player.js")]
      (when-not (fs/exists? built-player)
        (throw (ex-info "Compiled player asset is missing. Run npm run build:player first."
                        {:path (str built-player)})))
      (when-not (or (= (str player-target) (str built-player))
                    (fs/exists? player-target))
        (fs/copy built-player player-target)))))

(defn- extension [path]
  (or (second (re-find #"(\.[^./\\]+)$" (str path))) ".mp3"))

(defn- public-transcript [{:keys [config transcript names segments turns]}]
  (array-map
   :slug (:slug config)
   :title (or (:title config)
              (get-in transcript [:metadata :title])
              "Episode Transcript")
   :source (get-in transcript [:metadata :link] nil)
   :published (get-in transcript [:metadata :pubDate] nil)
   :duration_seconds (:duration_seconds transcript nil)
   :speaker_names names
   :segments segments
   :turns (mapv (fn [turn]
                  (array-map
                   :speaker (:speaker turn)
                   :start (:start turn)
                   :end (:end turn)
                   :phrase_ids (mapv :id (:phrases turn))))
                turns)))

(defn generate!
  ([] (generate! {}))
  ([opts]
   (let [config (merge default-config opts)
         transcript (pipeline-json/read-json (:transcript config))
         segments (normalize-segments transcript)
         _ (validate-segments segments)
         names (speaker-names transcript)
         turns (build-turns segments)
         out-dir (:out-dir config)
         episode-dir (fs/path out-dir "episodes" (:slug config))
         audio-dir (fs/path out-dir "assets" "audio")
         audio-file (str (:slug config) (extension (:audio config)))
         audio-target (fs/path audio-dir audio-file)
         public-transcript (public-transcript {:config config
                                               :transcript transcript
                                               :names names
                                               :segments segments
                                               :turns turns})
         html (render-html {:config config
                            :transcript transcript
                            :public-transcript public-transcript
                            :turns turns
                            :names names
                            :audio-href (str "../../assets/audio/" audio-file)})]
     (fs/create-dirs episode-dir)
     (fs/create-dirs audio-dir)
     (copy-shared-assets! out-dir)
     (fs/copy (:audio config) audio-target {:replace-existing true})
     (pipeline-json/write-json! (fs/path episode-dir "transcript.json") public-transcript)
     (spit (str (fs/path episode-dir "index.html")) html)
     (println (str "Generated " (fs/path episode-dir "index.html")))
     (println (str "Phrases: " (count segments)))
     (println (str "Turns: " (count turns)))
     {:public-transcript public-transcript
      :html html
      :segments segments
      :turns turns})))

(defn -main [& args]
  (generate! (parse-args args)))
