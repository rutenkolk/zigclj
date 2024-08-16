(ns zigclj.core
  "Functions for calling the zig compiler and using it in various ways"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as s]
   [clojure.pprint :as pprint]
   [clojure.java.shell :refer [sh with-sh-dir]]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [me.raynes.fs :as rfs]
   )
  (:import
   (clojure.lang
    IDeref IFn IMeta IObj IReference)
   (java.lang.invoke
    MethodHandle
    MethodHandles
    MethodType)
   (java.lang.foreign
    Linker
    Linker$Option
    FunctionDescriptor
    MemoryLayout
    MemorySegment
    SegmentAllocator)
  (java.util.zip ZipInputStream ZipEntry ZipFile GZIPInputStream)
  (org.apache.commons.compress.archivers.tar TarArchiveInputStream
                                             TarArchiveEntry)
  (org.apache.commons.compress.compressors.xz XZCompressorInputStream)
  (java.io ByteArrayOutputStream))
  )

(set! *warn-on-reflection* true)

(defn unxz-untar-in-memory
  ([input-stream]
   (unxz-untar-in-memory input-stream "./"))
  ([input-stream target-dir]
   (letfn [(tar-entries [tar-stream]
             (when-let [entry (.getNextTarEntry ^TarArchiveInputStream tar-stream)]
               (cons entry (lazy-seq (tar-entries tar-stream)))))]
     (let [tar-stream (TarArchiveInputStream. (XZCompressorInputStream. input-stream))
           entries (tar-entries tar-stream)]
       (doseq [^TarArchiveEntry entry (tar-entries tar-stream) :when (not (.isDirectory entry))
               :let [output-file (rfs/file target-dir (.getName entry))]]
         (rfs/mkdirs (rfs/parent output-file))
         (io/copy tar-stream output-file)
         ;;NOTE: idk if this actually necessary under linux / macos, let's test first
         #_(when (.isFile entry)
             (println "mode is:" (apply str (take-last
                                             3 (format "%05o" (.getMode entry)))))
             (rfs/chmod "777" ;(apply str (take-last 3 (format "%05o" (.getMode entry))))
                        (.getPath output-file)))
         )))))

(defn unzip-in-memory
  ([input-stream]
   (unzip-in-memory input-stream "./"))
  ([input-stream target-dir]
   (letfn [(zip-entries [zip-stream]
             (when-let [entry (.getNextEntry ^ZipInputStream zip-stream)]
               (cons entry (lazy-seq (zip-entries zip-stream)))))]
     (let [zip-stream (ZipInputStream. input-stream)
           entries (zip-entries zip-stream)]
       (doseq [^ZipEntry entry (zip-entries zip-stream) :when (not (.isDirectory entry))
               :let [output-file (rfs/file target-dir (.getName entry))]]
         (rfs/mkdirs (rfs/parent output-file))
         (io/copy zip-stream output-file))))))

(defn download-as-input-stream [url]
  (-> (client/get url {:as :byte-array})
      (:body)
      (io/input-stream)))

(def current-platform
  {:os (condp #(s/includes? %2 %1) (s/lower-case (System/getProperty "os.name"))
         "linux" ::linux
         "windows" ::windows
         ::macos)
   :arch (case (System/getProperty "os.arch")
           ("x86_64" "amd64") ::x86_64
           "x86" ::x86
           "aarch64" ::aarch64
           "riscv" ::riscv)})

(defn- truthy? [x] (if x true false))

(def zig-platform-specifier (str (name (:arch current-platform)) "-" (name (:os current-platform))))
(def zig-archive-extension (if (= ::windows (:os current-platform)) ".zip" ".tar.xz"))
(def zig-resource-name (str "zig-" (name (:os current-platform)) "-" (name (:arch current-platform))))
(def zig-resource-name-with-extension (str zig-resource-name zig-archive-extension))
(def extract! (case (:os current-platform) ::windows unzip-in-memory unxz-untar-in-memory))
(defn zig-is-on-path? [] (truthy? (try (:out (sh "zig" "version")) (catch Exception ex nil))))

(defn download-zig!
  ([] (download-zig! "master"))
  ([version-specifier]
    (letfn [(drop-file-extension [^String filename]
              (subs filename 0 (.lastIndexOf filename ".")))]
      (let [zig-json (json/parse-string (slurp "https://ziglang.org/download/index.json"))
            version (get-in zig-json [version-specifier "version"])
            dl-url (get-in zig-json [version-specifier zig-platform-specifier "tarball"])
            filename (subs dl-url (inc (.lastIndexOf ^String dl-url "/")))
            filename-seq (take-while #(s/includes? % zig-archive-extension) (iterate drop-file-extension filename))
            folder-name (drop-file-extension (last filename-seq))]
        (->
         dl-url
         (download-as-input-stream)
         (extract!))
        (.renameTo ^java.io.File (io/file folder-name) (io/file "zig-compiler"))))))

(def default-zig-version "0.13.0")

(defn extract-zig-from-resources! []
  (-> zig-resource-name-with-extension
      (io/resource)
      (io/input-stream)
      (extract!))
  (doseq [f (.listFiles (io/file "./"))]
    (if (s/includes? (.getName ^java.io.File f) zig-resource-name)
      (.renameTo ^java.io.File f (io/file "zig-compiler")))))

(defn- get-local-install-path "returns the local zig compiler path, or nil, if there is no local `zig-compiler` folder" []
  (if (.exists ^java.io.File (io/file "zig-compiler"))
    (if (= ::windows (:os current-platform)) "./zig-compiler/zig.exe" "./zig-compiler/zig")
    nil))

(defn prepare-zig!
  "prepares zig to be used with zigclj. returns the command to run zig (possibly path to the executable).

  in case of multiple instances of zig available, the function selects one according to the following priority list:

  1. zig in the local `zig-compiler` folder
  2. a native zig from an archive on the classpath that matches the `current-platform`
  3. a zig installation available on the PATH
  4. a freshly downloaded zig compiler from ziglang.org"
  []
  (cond
    (get-local-install-path) (get-local-install-path)
    (io/resource zig-resource-name-with-extension) (do (extract-zig-from-resources!) (prepare-zig!))
    (zig-is-on-path?) "zig"
    :default (download-zig! default-zig-version)))

(defn zig-command "returns the command to run zig (possibly path to the executable). returns nil if zig isn't available (yet). in this case, call prepare-zig! first." []
  (cond
    (get-local-install-path) (get-local-install-path)
    (zig-is-on-path?) "zig"
    :default nil))

(defn- smart-str [x]
  (cond
    (string? x) x
    (keyword? x) (name x)
    (seqable? x) (s/join " " (map smart-str x))
    :default (str x)))

(defn zig
  "call the zig compiler with command line arguments given via `args`. the arguments can be anything that can reasonably converted to a string (e.g. keywords).
  If both the exit code is 0 and there is no output on stderr, only the trimmed stdout is returned as a string.
  If either the exit code isn't 0 or the output to stderr isn't empty, a map is returned with the :exit code, as well as the strings corresponding to :out and :err.

  This function expects `zig-command` to return a non-nil value."
  [& args]
  (let [sh-args (apply vector (zig-command) (map smart-str args))
        cmd (s/join " " sh-args)
        {exit :exit out :out err :err :as ret} (apply sh sh-args)]
    (if (and (= exit 0) (empty? err))
      (s/trim-newline out)
      (assoc ret :cmd cmd))))

(def common-benign-untranslatables
  #{"va_start" "va_end" "va_arg" "va_copy"})

(defn- remove-duplicate-types [source]
  (-> source
      (s/replace
       #"(?m)pub const struct_([^=]+?) = ([^;]+?);\Rpub const ([^=]+?) = struct_(.+?);"
       (fn [[full tn1 body tn2 tn3]]
         (if (= tn1 tn2 tn3)
           (str "pub const " tn1 " = " body ";\n")
           full)))
      (s/replace
       #"(?m)pub const ([^=]+?) = struct_(.+?);\Rpub const struct_([^=]+?) = (.*?);"
       (fn [[full tn1 tn2 tn3 body]]
         (if (= tn1 tn2 tn3)
           (str "pub const " tn1 " = " body ";\n")
           full)))))

(defn- add-function-parameter-info-members [source]
  (->
   (s/replace
    source
    #"pub extern fn ([^\)]+?)\(([^\)]*?)\)[^;]*;"
    (fn [[full fn-name params-match]]
      (let [param-names (->> params-match
                             (re-seq #"\s?([^:,]+?):")
                             (map second)
                             (map #(s/replace % #"\"" ""))
                             (map #(s/replace % #"@" ""))
                             (map #(str \" % \"))
                             (s/join ", " ))]
        (str "\n" "pub const __zigclj_fn_param_names_" fn-name " = [_][]const u8{" param-names "};\n" full "\n" ))))
   (str
    "\npub const __zigclj_fn_param_names_CLITERAL = [_][]const u8{\"type\"}; "
    "// note: this exists solely to not generate compile errors in zig when reflecting over functions\n")))

(defn translate-c-header!
  "translate a c header file via 'zig translate-c' to a zig source file. on success, returns the new zig source file as a string.

  'translate-c' may fail to translate certain macros or expressions from c, in which case the compile-errors are returned as a map. Pass in a mapping that yields custom replacements for those errors via the optional 'opt' map argument under the key :compile-error-replacements. This can be a function, but for convenience, a map of string replacements is often enough.

  the custom replacements must map from the declaration name to a suitable replacement. the replacement itself can be either a string or something that can be converted to a string to replace the lines that correspond to the translation-error, or for more control, another function.

  In the case of a function, the replacement function for that translation failure is called with a map that contains
  the :full text to replace,
  the :name of the declaration that failed to translate,
  the :description the zig compiler generated for the @compileError builtin

  further options for the map are
  :remove-underscore : if truthy, will disregard any and all declarations that start with an underscore as those are often internal c-compiler macros, or semi-private symbols that don't matter for using the header file. (true by default)
  :remove-benign-errors : if truthy, will disregard all `common-benign-untranslatables` that are known (true by default)

  the replacements of the :compile-error-replacements take priority over other replacement options like :remove-benign-errors

  example usage:

  ```clojure
  (translate-c-header! \"raylib.h\"
   {:compile-error-replacements {\"RL_MALLOC\"  \"\\n\"
                                 \"RL_CALLOC\"  \"\\n\"
                                 \"RL_REALLOC\" \"\\n\"
                                 \"RL_FREE\"    \"\\n\"}})
  ```

  equivalently (in this case):

  ```clojure
  (translate-c-header! \"raylib.h\"
   {:compile-error-replacements #(if (clojure.string/starts-with? % \"RL_\") \"\\n\")})
  ```

  or even:

  ```clojure
  (translate-c-header! \"raylib.h\"
   {:compile-error-replacements
    {\"RL_MALLOC\" (fn [{:keys [full name description]}]
                   (str \"pub const \" name \" = \" (generate-custom-allocator-code) \";\\n\"))
     \"RL_CALLOC\"  \"\\n\"
     \"RL_REALLOC\" \"\\n\"
     \"RL_FREE\"    \"\\n\"}})
  ```
  "
  [header & {:keys [remove-underscore remove-benign-errors compile-error-replacements]
             :or {remove-underscore true
                  remove-benign-errors true
                  compile-error-replacements {}}
             :as opts}]
  (let [translate-c-raw (zig :translate-c header)
        comp-error-regex
        #"(?m)pub const (.*?) = @compileError.\"(.*?)\".*?\R\/\/.*?:(\d+?):(\d+?).*?\R"
        translate-c-processed
        (s/replace
         translate-c-raw
         comp-error-regex
         (fn [[full declname description line column :as match]]
           (let [match-map {:full full :name declname :description description}
                 usr-replace (compile-error-replacements declname)]
             (cond
               (truthy? usr-replace)
               (if (ifn? usr-replace) (usr-replace match-map) (smart-str usr-replace))
               (and remove-underscore (= \_ (first declname))) "\n"
               (and remove-benign-errors (common-benign-untranslatables declname)) "\n"
               :default full))))
        compile-errors-match (re-seq comp-error-regex translate-c-processed)
        compile-errors-list (->> compile-errors-match
                                 (map rest)
                                 (map (fn [[name msg line column]] [name {:name name :msg msg :line line :column column}])))
        compile-errors (into (hash-map) compile-errors-list)]
    (if (empty? compile-errors)
      translate-c-processed
      compile-errors)))

(defn post-process-header-translation
  "Removes the duplicate 'struct_' type 'translate-c' creates for every struct in a header and adds explicit information about function parameters."
  [zig-src]
  (-> zig-src
   (remove-duplicate-types)
   (add-function-parameter-info-members)))
