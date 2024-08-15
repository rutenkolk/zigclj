(ns build
  "tasks for building artifacts"
  (:require
   [clojure.java.io :as io]
   [clojure.tools.build.api :as b]
   [clojure.string :as s]
   [cheshire.core :as json]
   [clj-http.client :as client]
   ))

(def lib-coord 'wiredaemon/zigclj)
(def native-lib-prefix "zigclj-native")
(def version (format "0.13.%s" (b/git-count-revs nil)))
(def native-version "0.13.0")


(def resource-dirs ["resources/"])
(def source-dirs ["src/clj/"])

(def zig-compiler-archive-dir ["zig-archives/"])
(def target-dir "target/")
(def native-target-dir "native-target/")
(def class-dir (str target-dir "classes/"))
(def native-class-dir (str native-target-dir "classes/"))

(def basis (b/create-basis {:project "deps.edn"}))
(def native-basis (b/create-basis {:project "native-deps.edn"}))

(def jar-file (str target-dir "zigclj.jar"))

(defn clean [opts]
  (b/delete {:path target-dir})
  (b/delete {:path native-target-dir})
  opts)

(defn- exists? "Checks if file exists" [& paths]
  (.exists ^java.io.File (apply io/file paths)))

(defn- native-lib-coord [opts]
  (keyword "wiredaemon" (str native-lib-prefix "-" (name (:os opts)) "-" (name (:arch opts)))))

(defn- native-jar-file [opts]
  (str native-target-dir "zigclj-native.jar"))

(defn- zig-archive-extension [opts]
  (if (= "windows" (s/lower-case (name (:os opts)))) ".zip" ".tar.xz"))

(defn- write-native-pom [opts]
  (b/write-pom {:basis native-basis
                :class-dir native-class-dir
                :lib (native-lib-coord opts)
                :version native-version
                :scm {:url "https://github.com/rutenkolk/zigclj"
                      :connection "scm:git:git://github.com/rutenkolk/zigclj.git"
                      :developerConnection "scm:git:ssh://git@github.com/rutenkolk/zigclj.git"
                      :tag (str "v" native-version)}})
  (b/copy-file {:src (b/pom-path {:lib (native-lib-coord opts)
                                  :class-dir native-class-dir})
                :target (str native-target-dir "pom.xml")})
  opts)

(defn- write-pom "Writes a pom file" [opts]
  (b/write-pom {:basis basis
                :class-dir class-dir
                :lib lib-coord
                :version version
                :scm {:url "https://github.com/rutenkolk/zigclj"
                      :connection "scm:git:git://github.com/rutenkolk/zigclj.git"
                      :developerConnection "scm:git:ssh://git@github.com/rutenkolk/zigclj.git"
                      :tag (str "v" version)}
                :src-dirs source-dirs})
  (b/copy-file {:src (b/pom-path {:lib lib-coord
                                  :class-dir class-dir})
                :target (str target-dir "pom.xml")})
  opts)


(defn pom
  "Generates a `pom.xml` file in the `target/classes/META-INF` directory.
  If `:pom/output-path` is specified, copies the resulting pom file to it."
  [opts]
  (write-pom opts)
  (when-some [path (:output-path opts)]
    (b/copy-file {:src (b/pom-path {:lib lib-coord
                                    :class-dir class-dir})
                  :target path}))
  opts)

(defn native-pom [opts]
  (write-native-pom opts)
  (when-some [path (:output-path opts)]
    (b/copy-file {:src (b/pom-path {:lib (native-lib-coord opts)
                                    :class-dir class-dir})
                  :target path}))
  opts)

(defn- copy-resources
  "Copies the resources from the [[resource-dirs]] to the [[class-dir]]."
  [opts]
  (b/copy-dir {:target-dir class-dir
               :src-dirs resource-dirs})
  opts)

(defn- native-archive-path [opts]
  (str native-class-dir "zig-" (name (:os opts)) "-" (name (:arch opts)) (zig-archive-extension opts)))

(defn- zig-platform-specifier [opts]
  (str (name (:arch opts)) "-" (name (:os opts))))

(defn- download-and-add-zig-compiler
  "downloads an archive of the zig compiler for a platform and copies it to the [[native-class-dir]]."
  [opts]
  (letfn [(drop-file-extension [^String filename]
            (subs filename 0 (.lastIndexOf filename ".")))]
    (let [zig-json (json/parse-string (slurp "https://ziglang.org/download/index.json"))
          version (get-in zig-json [native-version "version"])
          dl-url (get-in zig-json [native-version (zig-platform-specifier opts) "tarball"])
          filename (subs dl-url (inc (.lastIndexOf ^String dl-url "/")))
          filename-seq (take-while #(s/includes? % (zig-archive-extension opts)) (iterate drop-file-extension filename))
          folder-name (drop-file-extension (last filename-seq))]
      (->
       dl-url
       (client/get {:as :byte-array})
       (:body)
       (io/input-stream)
       (io/copy (io/file (native-archive-path opts))))))
  opts)

(defn jar
  "Generates a `zigclj.jar` file in the `target/` directory"
  [opts]
  (write-pom opts)
  (copy-resources opts)
  (when-not (exists? target-dir jar-file)
    (b/copy-dir {:target-dir class-dir
                 :src-dirs source-dirs})
    (b/jar {:class-dir class-dir
            :jar-file jar-file}))
  opts)


(defn- native-jar
  "Generates a `zigclj-native.jar` file in the `native-target/` directory"
  [opts]
  (if (not (and (:arch opts) (:os opts)))
    (binding [*out* *err*]
      (println "error: no platform specified!"))
    (do
      (write-native-pom opts)
      (b/write-file {:path (native-archive-path opts)})
      ;(copy-resources opts)
      (download-and-add-zig-compiler opts)
      (b/jar {:class-dir native-class-dir
              :jar-file (native-jar-file opts)})))
  opts)

(defn run-tasks
  "Runs a series of tasks with a set of options.
  The `:tasks` key is a list of symbols of other task names to call. The rest of
  the option keys are passed unmodified."
  [opts]
  (println "opts are:")
  (clojure.pprint/pprint opts)
  (binding [*ns* (find-ns 'build)]
    (reduce
     (fn [opts task]
       ((resolve task) opts))
     opts
     (:tasks opts))))

